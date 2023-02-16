plugins {
    kotlin("multiplatform") version "1.7.22"
    id("io.kotest.multiplatform") version "5.5.5"
    `maven-publish`
}

group = "com.darkyen"
version = "0.3"

repositories {
    mavenCentral()
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

kotlin {
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
    }
    js(BOTH) {
        browser {
            testTask {
                useKarma {
                    useChromiumHeadless()
                    useFirefoxDeveloperHeadless()
                }
            }
        }
    }

    val kotest = "5.5.5"
    sourceSets {
        val commonMain by getting
        val commonTest by getting {
            dependencies {
                implementation("io.kotest:kotest-framework-engine:$kotest")
                implementation("io.kotest:kotest-assertions-core:$kotest")
            }
        }
        val jvmMain by getting
        val jvmTest by getting {
            repositories {
                maven("https://jitpack.io")
            }
            dependencies {
                implementation("com.github.EsotericSoftware:jsonbeans:0.9")
                implementation("io.kotest:kotest-runner-junit5:$kotest")
            }
        }
        val jsMain by getting
        val jsTest by getting
    }
}
