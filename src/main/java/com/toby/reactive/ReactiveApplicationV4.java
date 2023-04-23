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
public class ReactiveApplicationV4 {

	/**
	 * v4 : andApply 의 리턴 타입이 다른 문제를 제네릭을 사용하여 해결
	 */

	@RestController
	@RequestMapping(value = "/v4")
	public static class MyControllerV4 {
        public static final String URL1 = "http://localhost:8081/service?req={req}";
		public static final String URL2 = "http://localhost:8081/service2?req={req}";

		@Autowired  Myservice myservice;

		AsyncRestTemplate rt = new AsyncRestTemplate(new Netty4ClientHttpRequestFactory(new NioEventLoopGroup(1))); //non-blocking io 방식을 이용해서 외부 호출할 수 있는 라이브러리를 적용하는 방법 :  netty 등

		@GetMapping("/rest6")
		public DeferredResult<String> rest6(int idx) {
			DeferredResult<String> dr = new DeferredResult<>();

			Completion
					.from(rt.getForEntity(URL1, String.class, "hello" + idx)) // 첫번째 호출
					.andApply(s -> rt.getForEntity(URL2, String.class, s.getBody())) //두번째 호출
					.andApply(s -> myservice.work(s.getBody()))
					.andError(e -> dr.setErrorResult(e)) // 각단계에서 에러를 하나로 처리됨
					.andAccept(s -> dr.setResult(s)); //MVC에 값을 전달, s.getBody() : ListenableFuture의 결과값이 ResponseEntity<String> 타입이기 때문에

			return dr;
		}
	}

	/*
	S : 넘어온 파라미터, T : 리턴 결과값(Completion?)
	 */
	public static class Completion<S, T> {
		/*
		제네릭 적용 시 고려사항
		1. 클래스 레벨의 타입파라미터를 적용할지
		2. 메서드레벨에 추가된 타입파라미터를  적용할지
		 */

		Completion next; // 앞의 결과값을 다음 메소드로 전달하귀 위한 저장 값

		/*
			비동기 작업의 결과를 담는 용도
			스태틱이기 때문에 클래스의 파라미터와는 관계 없고 메서드레벨에 정의해야 한다.
			T : 내가 수행하기 때문
		 */
		public static <S, T> Completion<S, T> from(ListenableFuture<T> lf) {
			Completion<S, T> completion = new Completion<>();

			lf.addCallback(s ->  {
//				log.info("first callback s is = {}", s.getBody().toString());
				completion.complete(s);
			}, e-> {
				log.error("first callback error is = {}", e.getMessage());
				completion.error(e);
			});

			return completion;
		}

		/*
			T : andApply에 적용하는 Completion에서 생성하는 결과값
			V : 그다음 completion이 어떤 비동기 결과값을 가질지 모르기 때문에 메서드 레벨에 새로 등록
		 */
		public <V> Completion<T, V> andApply(Function<T, ListenableFuture<V>> fn) { //리턴값에도 타입 파라미터를 정의해야한다.
			Completion<T, V> completion = new ApplyCompletion<>(fn);
			this.next = completion;
			return completion;
		}

		/*
		 * ResponseEntity<String> : 앞의 실행의 겱과로써 리턴되는 것
		 * 요청을 Lamda 형식으로 보냈기 때문에 Consumer 등의 Functional Interface로 받아서 선어할 수 있다.
		 */
		public void andAccept(Consumer<T> consumer) {
			Completion<T, Void> completion = new AcceptCompletion(consumer);
			this.next = completion;
		}

		/*
		v3: void -> Completion : 에러가 발생하지 않으면 패스하고 다음 Completion으로 넘어가기 위함
		 */

		public Completion<T, T> andError(Consumer<Throwable> errorConsumer) {
			Completion<T,T> completion = new ErrorCompletion<>(errorConsumer); //T,T 정상적은 싱태에서는 값을 그대로 넘기기 때문에
			this.next = completion;
//			log.info("andError is called!!");
			return completion;
		}

		//T : Completion 내 자신이 수행한 결과를 받아오기 때문
		void complete(T s) {
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

		/*
			다음 completion을 호출하는 역할
			S : 앞의 Completion을 작업을 받아서 수행해야 하기 때문에 받는쪽의 타입이기 대문에 S
		 */
		void run(S value) {  //
			//다형성으로만 사용됨
		}

	}

	public static class ApplyCompletion<S, T> extends Completion<S, T> {
		public Function<S, ListenableFuture<T>> fn;

		public ApplyCompletion(Function<S, ListenableFuture<T>> fn) {
			this.fn = fn;
		}

		@Override
		void run(S value) { //S : 앞에서 넘어온걸 받기 때문에
			ListenableFuture<T> lf = fn.apply(value); //Function을 사용한 이유 apply를 사용하고 T, R을 사용하기 위함
			lf.addCallback(s -> {
//				log.info("second callback s is = {}", s.getBody().toString());
				complete(s);
			}, e -> {
				log.error("second callback error is = {}", e.getMessage());
				error(e);
			});
		}
	}

	/*
	 S : 앞단계에서 넘어온 타입
	 (T)Void : T타입(리턴) 타입이 consumer 에서는 의미가 없어서 void로 지정
	 */
	public static class AcceptCompletion<S> extends Completion<S,Void> {
		private Consumer<S> consumer;

		public AcceptCompletion(Consumer<S> consumer) {
			this.consumer = consumer;
		}

		@Override
		void run(S value) {
//			log.info("accept run is = {}", value.getBody().toString());
			consumer.accept(value); // 체크할 필요 없음 - AcceptCompletion 클래스가 사용되었다는 것은 이미 consumer 가 있다는 얘기
		}
	}

	public static class ErrorCompletion<T> extends Completion<T, T> {
		public Consumer<Throwable> errorConsumer; //ListenableFuture 의 addCallback된 error 을 받음 (Consumer는 내부 처리 방식에 대한 껍데기일 뿐)

		public ErrorCompletion(Consumer<Throwable> errorConsumer) {
			this.errorConsumer = errorConsumer;
		}

		@Override
		void run(T value) { // 정상의 경우에는 pass 하는 코드
			if (next != null) {
				next.run(value);
			}
		}

		@Override
		void error(Throwable e) {
			errorConsumer.accept(e);
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
		SpringApplication.run(ReactiveApplicationV4.class, args);
	}

}
