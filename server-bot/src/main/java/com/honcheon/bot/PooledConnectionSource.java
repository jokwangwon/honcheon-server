package com.honcheon.bot;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 풀 — PostgreSQL 의 샘. 여기서부터 전역 직렬화가 없다.
 *
 * <p><b>왜 손수 만들었나.</b> 이 봇의 동시성은 한 자릿수다 (디스코드 이벤트 몇 갈래 + 브리지 한 갈래).
 * 그 규모에 필요한 것은 "연결 N 개 · 겹침 대여 없음 · 죽은 연결 교체 · 고갈 시 소리내기"가 전부이고,
 * 그것은 눈에 다 보이는 코드여야 한다 — 감사가 소스를 재는 저장소이기 때문이다.
 * 규모가 커지면 그때 검증된 풀(HikariCP)로 갈아끼운다. 경계(ConnectionSource)는 이미 서 있다.
 *
 * <p><b>격리 수준을 여기서 건다.</b> 모든 연결이 같은 격리(PostgreSQL 은 SERIALIZABLE)로 태어난다 —
 * 같은 뭉치(aggregate)를 두 손이 동시에 고치면 <b>버전 충돌</b>이 나고, 그것이 순서의 판정이다
 * (설계 불변식: "행 잠금이나 버전 충돌로 순서를 판정한다").
 */
final class PooledConnectionSource extends BoundConnectionSource {

    /** 연결을 낳는 손 — 방언이 쥐고 있다. */
    @FunctionalInterface
    interface ConnectionFactory {
        Connection open() throws Exception;
    }

    /** 고갈을 무한정 참지 않는다 — 이만큼 기다려도 빈 손이면 소리내어 죽는다 (침묵 금지). */
    private static final long EXHAUSTED_SECONDS = 30;

    private final ConnectionFactory factory;
    private final int capacity;
    private final int isolation;   // 음수 = 드라이버 기본
    private final BlockingQueue<Connection> idle;
    private final AtomicInteger created = new AtomicInteger();
    private volatile boolean closed;

    // 관측 지표 — 풀이 어떻게 살았는지 사람이 물을 수 있다 (Db.storageStats)
    private final AtomicLong borrows = new AtomicLong();
    private final AtomicLong waits = new AtomicLong();
    private final AtomicLong waitMaxMillis = new AtomicLong();
    private final AtomicLong replacedDead = new AtomicLong();
    private final AtomicInteger inUse = new AtomicInteger();
    private final AtomicInteger inUsePeak = new AtomicInteger();

    PooledConnectionSource(ConnectionFactory factory, int capacity, int isolation) {
        this.factory = factory;
        this.capacity = Math.max(1, capacity);
        this.isolation = isolation;
        this.idle = new ArrayBlockingQueue<>(this.capacity);
    }

    @Override
    protected Connection checkout() throws Exception {
        if (closed) {
            throw new SQLException("샘이 닫혔다 — 저장소를 닫은 뒤의 대여다");
        }
        borrows.incrementAndGet();
        Connection candidate = idle.poll();
        if (candidate == null && created.get() < capacity) {
            // 게으른 탄생 — 쓰는 만큼만 만든다 (경합은 created 로 판정, 초과 낙찰은 되돌린다)
            if (created.incrementAndGet() <= capacity) {
                candidate = fresh();
            } else {
                created.decrementAndGet();
            }
        }
        if (candidate == null) {
            waits.incrementAndGet();
            long began = System.nanoTime();
            candidate = idle.poll(EXHAUSTED_SECONDS, TimeUnit.SECONDS);
            long waited = (System.nanoTime() - began) / 1_000_000;
            waitMaxMillis.accumulateAndGet(waited, Math::max);
            if (candidate == null) {
                throw new SQLException("풀 고갈 — " + EXHAUSTED_SECONDS + "초를 기다려도 빈 손이다"
                        + " (용량 " + capacity + " · 사용 중 " + inUse.get()
                        + ") — 트랜잭션이 연결을 쥔 채 잠들었는지 보라");
            }
        }
        if (candidate.isClosed()) {
            // 죽은 연결은 조용히 바꾼다 — 다만 세어 둔다 (자주 죽으면 그것이 신호다)
            replacedDead.incrementAndGet();
            candidate = fresh();
        }
        int using = inUse.incrementAndGet();
        inUsePeak.accumulateAndGet(using, Math::max);
        return candidate;
    }

    private Connection fresh() throws Exception {
        Connection connection = factory.open();
        if (isolation >= 0) {
            connection.setTransactionIsolation(isolation);
        }
        return connection;
    }

    @Override
    protected void checkin(Connection connection) {
        inUse.decrementAndGet();
        if (closed || !idle.offer(connection)) {
            quietClose(connection);
        }
    }

    @Override
    public String describe() {
        return "풀 " + capacity + " (생성 " + created.get() + ") · 사용 중 " + inUse.get()
                + " · 최고 동시 " + inUsePeak.get() + " · 대여 " + borrows.get()
                + "회 · 대기 " + waits.get() + "회 (최장 " + waitMaxMillis.get() + "ms)"
                + " · 죽은 연결 교체 " + replacedDead.get() + "회";
    }

    @Override
    public void close() {
        closed = true;
        for (Connection connection; (connection = idle.poll()) != null; ) {
            quietClose(connection);
        }
        // 사용 중인 연결은 반납되는 순간 checkin 이 닫는다 (closed 플래그)
    }

    private static void quietClose(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 닫다 실패한 연결에게 더 할 일이 없다
        }
    }
}
