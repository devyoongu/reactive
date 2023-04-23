package com.toby.reactive;

import io.netty.channel.nio.NioEventLoopGroup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.Netty4ClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.AsyncRestTemplate;
import org.springframework.web.context.request.async.DeferredResult;

import javax.annotation.PostConstruct;
import java.util.function.Consumer;
import java.util.function.Function;

@EnableAsync
@Slf4j
@SpringBootApplication
public class ReactiveApplicationV3 {

	@RestController
	@RequestMapping(value = "/v3")
	public static class MyControllerV3 {
        public static final String URL1 = "http://localhost:8081/service?req={req}";
		public static final String URL2 = "http://localhost:8081/service2?req={req}";

		AsyncRestTemplate rt = new AsyncRestTemplate(new Netty4ClientHttpRequestFactory(new NioEventLoopGroup(1))); //non-blocking io 방식을 이용해서 외부 호출할 수 있는 라이브러리를 적용하는 방법 :  netty 등

		@GetMapping("/rest6")
		public DeferredResult<String> rest6(int idx) {
			DeferredResult<String> dr = new DeferredResult<>();

			Completion
					.from(rt.getForEntity(URL1, String.class, "hello" + idx)) // 첫번째 호출
					.andApply(s -> rt.getForEntity(URL2, String.class, s.getBody())) //두번째 호출
					.andError(e -> dr.setErrorResult(e)) // 각단계에서 에러를 하나로 처리됨
//					.andError(e -> log.error("e log is = {}",e)) // 콜백 수행 시점 태스트
					.andAccept(s -> dr.setResult(s.getBody())); //MVC에 값을 전달, s.getBody() : ListenableFuture의 결과값이 ResponseEntity<String> 타입이기 때문에

			return dr;
		}
	}

	/**
	 * ReactiveApplicationV2 의 run 메서드의 다형성 적용
	 * v2 error 처리의 중복 해결
	 */

	public static class ApplyCompletion extends Completion {
		public Function<ResponseEntity<String>, ListenableFuture<ResponseEntity<String>>> fn;

		public ApplyCompletion(Function<ResponseEntity<String>, ListenableFuture<ResponseEntity<String>>> fn) {
			this.fn = fn;
		}

		@Override
		void run(ResponseEntity<String> value) {
			ListenableFuture<ResponseEntity<String>> lf = fn.apply(value); //Function을 사용한 이유 apply를 사용하고 T, R을 사용하기 위함
			lf.addCallback(s -> {
				log.info("second callback s is = {}", s.getBody().toString());
				complete(s);
			}, e -> {
				log.error("second callback error is = {}", e.getMessage());
				error(e);
			});
		}
	}

	public static class AcceptCompletion extends Completion {
		private Consumer<ResponseEntity<String>> consumer;

		public AcceptCompletion(Consumer<ResponseEntity<String>> consumer) {
			this.consumer = consumer;
		}

		@Override
		void run(ResponseEntity<String> value) {
			log.info("accept run is = {}", value.getBody().toString());
			consumer.accept(value); // 체크할 필요 없음 - AcceptCompletion 클래스가 사용되었다는 것은 이미 consumer 가 있다는 얘기
		}
	}

	public static class ErrorCompletion extends Completion {
		public Consumer<Throwable> errorConsumer; //ListenableFuture 의 addCallback된 error 을 받음 (Consumer는 내부 처리 방식에 대한 껍데기일 뿐)

		public ErrorCompletion(Consumer<Throwable> errorConsumer) {
			this.errorConsumer = errorConsumer;
		}

		@Override
		void run(ResponseEntity<String> value) { // 정상의 경우에는 pass 하는 코드
			if (next != null) {
				next.run(value);
			}
		}

		@Override
		void error(Throwable e) {
			errorConsumer.accept(e);
		}
	}

	public static class Completion {
		Completion next; // 앞의 결과값을 다음 메소드로 전달하귀 위한 저장 값

		public static Completion from(ListenableFuture<ResponseEntity<String>> lf) { //비동기 작업의 결과를 담는 용도
			Completion completion = new Completion();

			lf.addCallback(s ->  {
				log.info("first callback s is = {}", s.getBody().toString());
				completion.complete(s);
			}, e-> {
				log.error("first callback error is = {}", e.getMessage());
				completion.error(e);
			});

			return completion;
		}

		public Completion andApply(Function<ResponseEntity<String>, ListenableFuture<ResponseEntity<String>>> fn) {
			Completion completion = new ApplyCompletion(fn);
			this.next = completion;
			return completion;
		}

		/*
		 * ResponseEntity<String> : 앞의 실행의 겱과로써 리턴되는 것
		 * 요청을 Lamda 형식으로 보냈기 때문에 Consumer 등의 Functional Interface로 받아서 선어할 수 있다.
		 */
		public void andAccept(Consumer<ResponseEntity<String>> consumer) {
			Completion completion = new AcceptCompletion(consumer);
			this.next = completion;
		}

		public Completion andError(Consumer<Throwable> errorConsumer) { //void -> Completion : 에러가 발생하지 않으면 패스하고 다음 Completion으로 넘어가기 위함
			Completion completion = new ErrorCompletion(errorConsumer);
			this.next = completion;
//			log.info("andError is called!!");
			return completion;
		}

		void complete(ResponseEntity<String> s) {
			if (next != null) {
//				log.info("next is = {}", next);
				next.run(s); // 비동기 결과값을 다음 Completion에 전달한다.
			}
		}

		void error(Throwable e) {
			if (next != null) {
				next.error(e);
			}
		}

		void run(ResponseEntity<String> value) { // 다음 completion을 호출하는 역할
			//다형성으로만 사용됨
		}

	}

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
		SpringApplication.run(ReactiveApplicationV3.class, args);
	}

}
