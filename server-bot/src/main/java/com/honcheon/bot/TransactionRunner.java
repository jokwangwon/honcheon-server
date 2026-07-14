package com.honcheon.bot;

/** 업무 작업 전체를 하나의 저장소 트랜잭션으로 실행하는 포트. */
interface TransactionRunner {
    @FunctionalInterface
    interface Work<T> {
        T run() throws Exception;
    }

    <T> T inTransaction(Work<T> work) throws Exception;
}
