package com.honcheon.bot;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 스레드 결속 — 겹친 borrow 가 <b>같은 연결</b>을 받도록 세는 뼈대.
 *
 * <p>왜 세는가: 한 메서드가 문장을 연 채 다른 저장소 메서드를 부르고, 그 위에 트랜잭션이
 * 겹친다. 그때마다 샘에서 <b>다른</b> 연결을 꺼내 주면 — 풀에서는 자기 자신과 교착하고,
 * 트랜잭션은 반쪽짜리가 된다. 그래서 스레드마다 연결 하나를 붙들고 깊이만 센다.
 * 깊이가 0 으로 돌아오는 순간 연결이 샘으로 돌아간다.
 */
abstract class BoundConnectionSource implements ConnectionSource {

    private static final class Bound {
        Connection connection;
        int depth;
    }

    private final ThreadLocal<Bound> bound = new ThreadLocal<>();

    @Override
    public final Connection borrow() throws Exception {
        Bound b = bound.get();
        if (b != null) {
            b.depth++;
            return b.connection;
        }
        Connection fresh = checkout();
        b = new Bound();
        b.connection = fresh;
        b.depth = 1;
        bound.set(b);
        return fresh;
    }

    @Override
    public final void release(Connection ignored) {
        Bound b = bound.get();
        if (b == null) {
            return;   // 짝 없는 반납 — 죽은 연결 정리 경로에서 온다. 조용히 무시가 안전하다
        }
        if (--b.depth == 0) {
            bound.remove();
            checkin(b.connection);
        }
    }

    @Override
    public final boolean inTransaction() {
        Bound b = bound.get();
        if (b == null) {
            return false;
        }
        try {
            return !b.connection.getAutoCommit();
        } catch (SQLException broken) {
            return false;   // 연결이 죽었다면 트랜잭션도 없다
        }
    }

    /** 샘에서 실제로 꺼낸다 — 단일이면 잠그고, 풀이면 고른다. */
    protected abstract Connection checkout() throws Exception;

    /** 샘으로 실제로 돌려보낸다. */
    protected abstract void checkin(Connection connection);
}
