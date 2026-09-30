plugins {
    java
    id("org.springframework.boot") version "3.5.5"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.ballknowers"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // specs/009-auto-data-refresh R12: no test may trigger a background refresh by visiting a page.
    systemProperty("refresh.on-visit.enabled", "false")
    // claude/audit-2026-09-28/01: a known admin token for the test JVM, so an IT can present it
    // (see TestAdmin). A blank ADMIN_TOKEN in a dev shell would otherwise disable admin and make
    // the suite depend on the environment. A test that wants admin disabled overrides this with a blank.
    systemProperty("draftsim.admin.token", "it-admin-token")
    // Every distinct @SpringBootTest configuration caches its own context, and each context opens a
    // Hikari pool of DB_POOL_SIZE (default 10) that stays open for the whole JVM. Postgres's default
    // max_connections is 100, so around ten cached contexts starve the rest -- which shows up as a
    // silent SKIP (each IT's @BeforeAll assumption fails to connect), not a failure. Measured
    // 2026-09-29: adding three contexts here took 74 ITs from run to skipped. A small pool is plenty
    // for a test JVM.
    systemProperty("spring.datasource.hikari.maximum-pool-size", "3")
}
