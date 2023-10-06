package com.toby.reactive;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@EnableAsync
@Slf4j
@SpringBootApplication
public class ReactiveApplicationV5 {

	/**
	 * v5 : java8 의 CompletableFuture 활용
	 */

	public static void main(String[] args) throws ExecutionException, InterruptedException {
//		CompletableFuture<Integer> f = CompletableFuture.completedFuture(1); // 완료된 상태
		CompletableFuture<Integer> f = new CompletableFuture<>();
//		f.complete(2); // 비동기 작업을 완료
		f.completeExceptionally(new RuntimeException());
		System.out.println("f = " + f.get());


	}

}
