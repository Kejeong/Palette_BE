// API Gateway (Spring Cloud Gateway, WebFlux). 서비스별 경로 나누기와 JWT 검사를 맡는다.
// WebFlux라서 webmvc를 api로 주는 common에는 의존하지 않는다.
// Java 21·Lombok·Boot BOM·테스트 설정은 루트 allprojects에서 온다.
plugins {
	id("org.springframework.boot")
}

dependencies {
	implementation(platform("org.springframework.cloud:spring-cloud-dependencies:2025.1.4"))
	implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")   // WebTestClient
}
