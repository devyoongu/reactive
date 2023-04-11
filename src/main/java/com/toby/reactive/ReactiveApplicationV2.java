package com.toby.reactive;

import io.netty.channel.nio.NioEventLoopGroup;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.Netty4ClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.AsyncRestTemplate;
import org.springframework.web.context.request.async.DeferredResult;

import javax.annotation.PostConstruct;
import java.util.function.Consumer;

@EnableAsync
@Slf4j
@SpringBootApplication
public class ReactiveApplicationV2 {

	@RestController
	@RequestMapping(value = "/v2")
	public static class MyControllerV2 {

        public static final String URL1 = "http://localhost:8081/service?req={req}";
		public static final String URL2 = "http://localhost:8081/service?req={req}";
		@Autowired Myservice myservice;

		AsyncRestTemplate rt = new AsyncRestTemplate(new Netty4ClientHttpRequestFactory(new NioEventLoopGroup(1))); //non-blocking io 방식을 이용해서 외부 호출할 수 있는 라이브러리를 적용하는 방법 netty 등

		/*
		MyService : 내부 작업을 추가한 버전
		 */

		@GetMapping("/rest5")
		public DeferredResult<String> rest5(int idx) {

			DeferredResult<String> dr = new DeferredResult<>();

			ListenableFuture<ResponseEntity<String>> f1 = rt.getForEntity(URL1, String.class, "hello" + idx);
			f1.addCallback(s -> {
					ListenableFuture<ResponseEntity<String>> f2 = rt.getForEntity(URL2, String.class, s.getBody());
					f2.addCallback(s2-> {
						ListenableFuture<String> f3 = myservice.work(s2.getBody()); // 내부 비동기 작업을 추가
						f3.addCallback(s3-> {
								dr.setErrorResult(s3);
							}, e -> {
								dr.setErrorResult(e.getMessage());
							}
						);
						dr.setResult(s2.getBody() + "/work");
					}, e -> {
						dr.setErrorResult(e.getMessage());
					});
				}, e-> {
					dr.setErrorResult(e.getMessage());
				}
			);

			return dr;
		}

		@GetMapping("/rest6")
		public DeferredResult<String> rest6(int idx) {
			DeferredResult<String> dr = new DeferredResult<>();

			Completion
					.from(rt.getForEntity(URL1, String.class, "hello" + idx))
					.andApply(s -> rt.getForEntity(URL2, String.class, s.getBody()))
					.andAccept(s -> dr.setResult(s.getBody())); //ListenableFuture의 결과값이 ResponseEntity<String> 타입이기 때문에


			return dr;
		}
	}




	public static class Completion {

		Completion next;

		private Consumer<ResponseEntity<String>> consumer;

		public Completion(Consumer<ResponseEntity<String>> consumer) {
			this.consumer = consumer;
		}

		public Completion() {
		}

		public static Completion from(ListenableFuture<ResponseEntity<String>> lf) {
			Completion completion = new Completion();

			lf.addCallback(s ->  {
				completion.complete(s);
			}, e-> {
				completion.error(e);
			});

			return completion;
		}

		public void andAccept(Consumer<ResponseEntity<String>> consumer) {
			Completion completion = new Completion(consumer);
			this.next = completion;
		}

		void error(Throwable e) {
		}

		void complete(ResponseEntity<String> s) {
			if (next != null) {
				next.run(s);
			}

		}

		void run(ResponseEntity<String> value) { // 앞의 비동기 작업이 넘어왔으니 나한테 등록된 컨슈머가 있다고 하면 앞의 작업의 결과를 넘겨준다?
			if (consumer != null) {
				consumer.accept(value);
			}


		}


	}






	//비동기 내부 작업을 추가한 케이스
	@Service
	public static class Myservice {
		@Async
		public ListenableFuture<String> work(String req) {
			return new AsyncResult<>(req + "/asyncwokr");
		}
	}

	/*//Thread  갯수 제한 (Async 시 쓰레드가 무한대로 생기는 것을 방지하기 위함)
	@Bean
	public ThreadPoolTaskExecutor myThreadPool() {
		ThreadPoolTaskExecutor te = new ThreadPoolTaskExecutor(); // 기본은 큐가 무한대
		//큐를 먼저 채우고 큐까지 다 찼을 때 MaxPoolSize 까지 쓰레드를 늘렸다가 MaxPoolSize도 다 차면 그때 에러가 난다.
		//core가 다 차면 MaxPoolSize까지 찬다.
		te.setCorePoolSize(1);
		te.setMaxPoolSize(1);
		te.initialize();
		return te;
	}*/


	@PostConstruct
	public void init() {
		System.out.println("max-connections: " + environment.getProperty("server.tomcat.max-connections"));
		System.out.println("accept-count: " + environment.getProperty("server.tomcat.accept-count"));
		System.out.println("max-threads: " + environment.getProperty("server.tomcat.max-threads"));
		System.out.println("port: " + environment.getProperty("server.port"));
	}

	@Autowired
	private Environment environment;

	public static void main(String[] args) {
		SpringApplication.run(ReactiveApplicationV2.class, args);
	}

}
