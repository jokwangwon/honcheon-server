package com.honcheon.bot;

import java.sql.Connection;

/**
 * 연결의 샘 — 스레드에 연결을 빌려주고 돌려받는다. PG-006 의 동시성 경계.
 *
 * <p><b>왜 이것이 필요한가.</b> PG-005 까지 {@code Db} 는 <b>연결 하나</b>를 전역
 * {@code synchronized} 로 지켰다 — 읽기와 쓰기가 전부 한 줄로 섰다. PostgreSQL 로 옮긴
 * 이유가 동시 사용자인데, 문 앞에서 다시 한 줄로 세우면 옮긴 뜻이 없다.
 *
 * <p>그래서 직렬화를 <b>여기</b>로 내린다: SQLite 는 {@link SingleConnectionSource}(한 손 —
 * 오늘과 같은 직렬화), PostgreSQL 은 {@link PooledConnectionSource}(풀 — 진짜 동시성).
 * 위의 코드는 어느 쪽인지 모른다.
 *
 * <p><b>빌림의 규약</b>: 같은 스레드의 겹친 borrow 는 <b>같은 연결</b>을 돌려준다
 * (문장 열기 → 그 안에서 다른 저장소 호출 → 트랜잭션, 전부 한 연결이어야 한다).
 * borrow 와 release 는 짝이다 — 짝이 다 풀린 순간 연결이 샘으로 돌아간다.
 */
interface ConnectionSource extends AutoCloseable {

    /** 이 스레드의 연결 — 이미 빌려 갔다면 같은 것을 (겹침 셈), 아니면 샘에서 꺼내 준다. */
    Connection borrow() throws Exception;

    /** 짝 반납 — 마지막 짝이 풀리면 연결이 샘으로 돌아간다. */
    void release(Connection connection);

    /** 이 스레드가 지금 업무 트랜잭션(자동 커밋 꺼짐) 안인가 — 합류 판정에 쓴다. */
    boolean inTransaction();

    /** 관측 지표 — 사람이 읽는 한 줄 (풀 크기 · 사용 중 · 대기 횟수 …). */
    String describe();

    @Override
    void close() throws Exception;
}
