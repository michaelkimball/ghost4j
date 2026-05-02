plugins {
    java
    `java-library`
    `maven-publish`
    signing
    alias(libs.plugins.spotless)
    alias(libs.plugins.nmcp)
    alias(libs.plugins.nmcp.aggregation)
}

group = "io.github.michaelkimball"
// Version is set from the git tag in CI (e.g. refs/tags/v1.2.3 → 1.2.3).
// Falls back to "1.2.0-SNAPSHOT" for local development.
version = System.getenv("RELEASE_VERSION") ?: "1.2.0-SNAPSHOT"
val githubRepo = System.getenv("GITHUB_REPOSITORY") ?: "michaelkimball/ghost4j"
description = "Java wrapper for Ghostscript API."

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    api(libs.jna)
    api(libs.slf4j.api)
    api(libs.commons.beanutils)
    api(libs.xmlgraphics.commons)
    api(libs.itext.kernel)
    api(libs.itext.layout)
    api(libs.itext.io)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:none", "-quiet")
    }
}

spotless {
    java {
        googleJavaFormat(libs.versions.google.java.format.get()).aosp()
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("Ghost4J")
                description.set(project.description)
                url.set("https://github.com/$githubRepo")
                licenses {
                    license {
                        name.set("GNU LESSER GENERAL PUBLIC LICENSE")
                        url.set("http://www.gnu.org/licenses/lgpl-3.0-standalone.html")
                    }
                }
                developers {
                    developer {
                        name.set("Michael Kimball")
                        email.set("me@michaelkimball.dev")
                    }
                }
                contributors {
                    contributor {
                        name.set("Gilles Grousset")
                        email.set("gi.grousset@gmail.com")
                        url.set("http://zippy1978.tumblr.com")
                    }
                    contributor {
                        name.set("Dave Smith")
                        email.set("dave.smith@candata.com")
                        organization.set("CANdata Systems")
                        organizationUrl.set("http://www.candata.com")
                    }
                    contributor {
                        name.set("Michael Sliwak")
                        email.set("msliwak@googlemail.com")
                    }
                    contributor {
                        name.set("squallssck")
                        email.set("squallssck@gmail.com")
                        url.set("http://techfee.com")
                    }
                    contributor {
                        name.set("BusyBusinessCat")
                        url.set("https://github.com/BusyBusinessCat")
                    }
                    contributor {
                        name.set("O.J. Sousa Rodrigues")
                        email.set("osoriojaques@gmail.com")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/$githubRepo.git")
                    developerConnection.set("scm:git:ssh://github.com/$githubRepo.git")
                    url.set("https://github.com/$githubRepo")
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/${System.getenv("GITHUB_REPOSITORY") ?: githubRepo}")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

nmcpAggregation {
    centralPortal {
        username = System.getenv("SONATYPE_USERNAME") ?: ""
        password = System.getenv("SONATYPE_PASSWORD") ?: ""
        publishingType = "AUTOMATIC"
    }
}

dependencies {
    nmcpAggregation(project(":"))
}

signing {
    val signingKey = System.getenv("GPG_PRIVATE_KEY")
    val signingPasswd = System.getenv("GPG_PASSPHRASE")
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPasswd)
        sign(publishing.publications["mavenJava"])
    }
}
