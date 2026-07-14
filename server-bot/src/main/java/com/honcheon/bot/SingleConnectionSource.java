package com.honcheon.bot;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 한 손 — 연결 하나를 잠금으로 지킨다. SQLite 의 샘이다.
 *
 * <p>PG-005 까지의 전역 {@code synchronized} 가 하던 일이 <b>여기로 내려왔다</b>:
 * 그때는 메서드 전체가 잠겼고, 지금은 빌림(문장·트랜잭션)의 수명만 잠긴다.
 * SQLite 파일 하나에 동시 쓰기는 어차피 없다 — 이 직렬화가 곧 그 사실의 표현이다.
 */
final class SingleConnectionSource extends BoundConnectionSource {

    private final Connection connection;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong borrows = new AtomicLong();

    SingleConnectionSource(Connection connection) {
        this.connection = connection;
    }

    @Override
    protected Connection checkout() {
        lock.lock();
        borrows.incrementAndGet();
        return connection;
    }

    @Override
    protected void checkin(Connection ignored) {
        lock.unlock();
    }

    @Override
    public String describe() {
        return "단일 연결 (직렬) · 대여 " + borrows.get() + "회";
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
