package com.toby.reactive;

import io.netty.channel.nio.NioEventLoopGroup;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.AsyncRestTemplate;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.async.DeferredResult;

import javax.annotation.PostConstruct;

@EnableAsync
@Slf4j
@SpringBootApplication
public class ReactiveApplication {

	@RestController
	public static class MyController {


		public static final String HTTP_LOCALHOST_8081_SERVICE_REQ_REQ1 = "http://localhost:8081/service?req={req}";
		public static final String HTTP_LOCALHOST_8081_SERVICE_REQ_REQ2 = "http://localhost:8081/service2?req={req}";
		@Autowired Myservice myservice;



		RestTemplate rt = new RestTemplate();

		NioEventLoopGroup eventLoopGroup = new NioEventLoopGroup(1);
		AsyncRestTemplate rt2 = new AsyncRestTemplate(new Netty4ClientHttpRequestFactory(eventLoopGroup));

//		AsyncRestTemplate rt2 = new AsyncRestTemplate();

		//기본적으로 쓰레드는 프로세스 갯수 * 2
		AsyncRestTemplate rt3 = new AsyncRestTemplate(new Netty4ClientHttpRequestFactory(new NioEventLoopGroup(1))); //non-blocking io 방식을 이용해서 외부 호출할 수 있는 라이브러리를 적용하는 방법 netty 등

		//Total: 100.00576843
		@GetMapping("/rest")
		public String rest(int idx) {
			log.info("idx is = {}",idx);

			String res = rt.getForObject("http://localhost:8081/service?req={req}", String.class, "hello" + idx);
			return res+":" + idx;
		}

		/*
			ver2 : 조삼모사 버전
			http-nio-8080-exec는 현재 1개만 받도록 설정.
			AsyncRestTemplate 사용하였기 때문에 요청 갯수에 따라  SimpleAsyncTaskExecutor 100개가 (노캐싱) 쓰레드를 만들어서 구동
		 */

		@GetMapping("/rest2")
		public ListenableFuture<ResponseEntity<String>> rest2(int idx) {
			//비동기 호출 후 결과를 처리하는 콜백 따로 등록하지 않아도 된다. -> 스프링 MVC가 처리해준다.
			return rt2.getForEntity("http://localhost:8081/service?req={req}", String.class, "hello" + idx);//getForEntity: 헤더와 응답코드까지 받는다.
		}

		/*
			ver3 : 최소한의 쓰레드를 사용하는 버전\
			ver2 와 실행시간은 같음
			netty를 위한 쓰레드 외에는 거의 추가된게 없음 (nioEventLoopGroup-2-1 쓰레드만 추가 생성됨)
			todo : 어느정도 진행되다가 다량의 건이 503 오류 발생
			- remote는 계속 요청을 처리하고 있고 application은  Resolved [org.springframework.web.context.request.async.AsyncRequestTimeoutException] 발생
			- AsyncRestTemplate 으로 별도 워커 쓰레드가 생성되지 않고 동기적으로 호출하는 것으로 보임 0329

		*/

		@GetMapping("/rest3")
		public ListenableFuture<ResponseEntity<String>> rest3(int idx) {
			//비동기 호출 후 결과를 처리하는 콜백 따로 등록하지 않아도 된다. -> 스프링 MVC가 처리해준다.
			return rt3.getForEntity("http://localhost:8081/service?req={req}", String.class, "hello" + idx);//getForEntity: 헤더와 응답코드까지 받는다.
		}


		/*
			ver4 : 비동기 ListenableFuture로 리턴된 값을DeferredResult 에 값을 써줘서 리턴하는 방식
			측정 결과 :
				- 쓰레드 : 왜 40개지?
				- 시간 :Total: 4.256036863
		*/

		@GetMapping("/rest4")
		public DeferredResult<String> rest4(int idx) {

			DeferredResult<String> dr = new DeferredResult<>(); // DeferredResult에 값을 써주면 그때 응답이 된다.

			//비동기 호출 후 결과를 처리하는 콜백 따로 등록하지 않아도 된다. -> 스프링 MVC가 처리해준다.
			ListenableFuture<ResponseEntity<String>> f1 = rt3.getForEntity(HTTP_LOCALHOST_8081_SERVICE_REQ_REQ1, String.class, "hello" + idx);//getForEntity: 헤더와 응답코드까지 받는다.
			//ListenableFuture 에서 결괏값을 꺼내는 방식 : 직접 꺼내는 방식은 blocking 이라 안됨
			// -> 콜백 구조로 만들어야 한다. (정상, 에러) 콜백은 실행만 하고 리턴하지 않는다.
			// 때문에 DeferredResult를 사용해서 리턴해야 한다.

			f1.addCallback(
					s -> {
						ListenableFuture<ResponseEntity<String>> f2 = rt3.getForEntity(HTTP_LOCALHOST_8081_SERVICE_REQ_REQ2,
								String.class, s.getBody());//getForEntity: 헤더와 응답코드까지 받는다.

						f2.addCallback(s2-> {
							dr.setResult(s2.getBody() + "/work");
						}, e -> {
							dr.setErrorResult(e.getMessage());
						});


					}, e-> { // 콜백방식은 어떤 stack trace 통해서 예외가 발생하는지 모르기 때문에 전파하는건 무의미하기 때문에 응답값에 메세지 등을 담아서 보낸다.
						dr.setErrorResult(e.getMessage());
					}
			);

			return dr;
		}


		/*
		MyService : 내부 작업을 추가한 버전
		단점
		1. 콜백지옥
		2. 에러처리가 중복됨
		 */

		@GetMapping("/rest5")
		public DeferredResult<String> rest5(int idx) {

			DeferredResult<String> dr = new DeferredResult<>();

			ListenableFuture<ResponseEntity<String>> f1 = rt3.getForEntity(HTTP_LOCALHOST_8081_SERVICE_REQ_REQ1, String.class, "hello" + idx);
			f1.addCallback(s -> {
					ListenableFuture<ResponseEntity<String>> f2 = rt3.getForEntity(HTTP_LOCALHOST_8081_SERVICE_REQ_REQ2, String.class, s.getBody());
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
	}

	@Autowired
	private Environment environment;

	public static void main(String[] args) {

//		String maxConnections = System.getProperty("server.tomcat.max-connections");
//		String acceptCount = System.getProperty("server.tomcat.accept-count");
//		String maxThreads = System.getProperty("server.tomcat.max-threads");


//		System.out.println("Max Connections: " + maxConnections);
//		System.out.println("Accept Count: " + acceptCount);
//		System.out.println("Max Threads: " + maxThreads);


		SpringApplication.run(ReactiveApplication.class, args);

//		String port = System.getProperty("server.port");
//		System.out.println("port: " + port);

	}


	//비동기 내부 작업을 추가한 케이스
	@Service
	public static class Myservice {
		@Async
		public ListenableFuture<String> work(String req) {
			return new AsyncResult<>(req + "/asyncwokr");
		}
	}

	//Thread  갯수 제한 (Async 시 쓰레드가 무한대로 생기는 것을 방지하기 위함)
	@Bean
	public ThreadPoolTaskExecutor myThreadPool() {
		ThreadPoolTaskExecutor te = new ThreadPoolTaskExecutor(); // 기본은 큐가 무한대
		//큐를 먼저 채우고 큐까지 다 찼을 때 MaxPoolSize 까지 쓰레드를 늘렸다가 MaxPoolSize도 다 차면 그때 에러가 난다.
		//core가 다 차면 MaxPoolSize까지 찬다.
		te.setCorePoolSize(1);
		te.setMaxPoolSize(1);
		te.initialize();
		return te;
	}


	@PostConstruct
	public void init() {
		System.out.println("max-connections: " + environment.getProperty("server.tomcat.max-connections"));
		System.out.println("accept-count: " + environment.getProperty("server.tomcat.accept-count"));
		System.out.println("max-threads: " + environment.getProperty("server.tomcat.max-threads"));
		System.out.println("port: " + environment.getProperty("server.port"));
	}

}
