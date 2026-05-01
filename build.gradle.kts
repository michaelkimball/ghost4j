plugins {
    java
    `java-library`
    `maven-publish`
    alias(libs.plugins.spotless)
}

group = "io.github.michaelkimball"
version = "1.1.0"
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
                url.set("https://github.com/michaelkimball/ghost4j")
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
            }
        }
    }
}
