package com.honcheon.bot;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 길잡이 연결 — {@code Db} 의 87곳 {@code conn.} 이 그대로 살게 하는 가면.
 *
 * <p><b>왜 가면인가.</b> PG-006 은 연결 하나를 풀로 바꾼다. 정직한 길은 87곳의 SQL 자리마다
 * "빌리고 → 쓰고 → 돌려주고"를 심는 것인데, 그 대수술은 실수 한 곳이 곧 연결 누수다.
 * 대신 {@link Connection} 의 가면을 씌운다: 코드가 늘 하던 대로 문장을 열면 <b>그 순간</b> 빌리고,
 * 문장이 닫히면 돌려준다. try-with-resources 가 이 저장소의 집안 문법이므로 반납은 문법이 보증한다.
 *
 * <p><b>세 가지 몸짓만 안다</b> — 그 밖의 몸짓은 소리내어 거절한다 (조용한 오동작 금지):
 * <ul>
 *   <li><b>문장 열기</b> (prepareStatement·createStatement) — 빌리고, 문장의 close 에서 돌려준다</li>
 *   <li><b>트랜잭션</b> (setAutoCommit(false) … commit/rollback … setAutoCommit(true)) —
 *       끄는 순간 빌려 <b>고정</b>하고, 켜는 순간 돌려준다. 그 사이의 모든 문장은 같은 연결이다</li>
 *   <li><b>닫기</b> (close) — 샘 전체를 닫는다</li>
 * </ul>
 *
 * <p>연결 상태를 직접 만지는 일(격리 변경·unwrap·스냅숏)은 가면 너머로 못 한다 —
 * 그런 손은 {@code source.borrow()} 로 실제 연결을 받아라 (Db 의 스냅숏 자리가 그렇게 한다).
 */
final class RoutingConnection implements InvocationHandler {

    private final ConnectionSource source;

    private RoutingConnection(ConnectionSource source) {
        this.source = source;
    }

    static Connection wrap(ConnectionSource source) {
        return (Connection) Proxy.newProxyInstance(RoutingConnection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, new RoutingConnection(source));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        // Object 의 몸짓 — 가면 자신이 답한다
        switch (name) {
            case "toString" -> {
                return "RoutingConnection(" + source.describe() + ")";
            }
            case "hashCode" -> {
                return System.identityHashCode(proxy);
            }
            case "equals" -> {
                return proxy == args[0];
            }
            default -> {
            }
        }
        return switch (name) {
            case "prepareStatement", "createStatement", "prepareCall" -> statement(method, args);
            case "setAutoCommit" -> setAutoCommit((Boolean) args[0]);
            case "getAutoCommit" -> !source.inTransaction();
            case "commit", "rollback" -> onBound(method, args);
            case "close" -> {
                source.close();
                yield null;
            }
            case "isClosed" -> false;
            default -> throw new SQLException("RoutingConnection 은 " + name + " 을 모른다 —"
                    + " 연결 상태를 직접 만지는 손은 source.borrow() 로 실제 연결을 받아라");
        };
    }

    /** 문장 열기 — 빌리고, 돌려주는 일은 문장의 close 에 묶는다. */
    private Object statement(Method method, Object[] args) throws Exception {
        Connection real = source.borrow();
        try {
            Object statement = method.invoke(real, args);
            Class<?> face = statement instanceof CallableStatement ? CallableStatement.class
                    : statement instanceof PreparedStatement ? PreparedStatement.class
                    : Statement.class;
            return Proxy.newProxyInstance(RoutingConnection.class.getClassLoader(),
                    new Class<?>[]{face}, (p, m, a) -> {
                        boolean closing = "close".equals(m.getName());
                        try {
                            return m.invoke(statement, a);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        } finally {
                            if (closing) {
                                source.release(real);   // 문장이 닫히면 연결이 샘으로 돌아간다
                            }
                        }
                    });
        } catch (Exception failure) {
            source.release(real);   // 문장을 열다 실패해도 빌린 것은 돌려준다
            throw unwrap(failure);
        }
    }

    /** 트랜잭션의 문 — 끄면 빌려 고정, 켜면 반납. */
    private Object setAutoCommit(boolean on) throws Exception {
        if (!on) {
            Connection real = source.borrow();   // 고정 — setAutoCommit(true) 까지 이 스레드의 것
            try {
                real.setAutoCommit(false);
            } catch (Exception failure) {
                source.release(real);
                throw unwrap(failure);
            }
            return null;
        }
        if (!source.inTransaction()) {
            return null;   // 이미 자동 커밋 — 오늘과 같은 무해한 no-op
        }
        Connection real = source.borrow();
        try {
            real.setAutoCommit(true);
        } finally {
            source.release(real);   // 이번 빌림의 짝
            source.release(real);   // 고정(setAutoCommit(false))의 짝 — 여기서 트랜잭션이 끝난다
        }
        return null;
    }

    /** 고정된 연결 위에서만 뜻이 있는 몸짓 (commit·rollback). */
    private Object onBound(Method method, Object[] args) throws Exception {
        Connection real = source.borrow();
        try {
            return method.invoke(real, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause() instanceof Exception e ? e : failure;
        } finally {
            source.release(real);
        }
    }

    private static Exception unwrap(Exception failure) {
        if (failure instanceof InvocationTargetException ite) {
            if (ite.getCause() instanceof Exception cause) {
                return cause;
            }
            if (ite.getCause() instanceof Error error) {
                throw error;
            }
        }
        return failure;
    }
}
