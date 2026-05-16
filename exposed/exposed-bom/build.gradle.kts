plugins {
    `java-platform`
    `maven-publish`
    signing
}

dependencies {
    constraints {
        rootProject.subprojects {
            if (name != "exposed-bom" &&
                !projectDir.absolutePath.contains("/examples/") &&
                !name.endsWith("-demo")
            ) {
                api(this)
            }
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("BluetapeExposed") {
            from(components["javaPlatform"])
            pom {
                name.set("exposed-bom")
                description.set("BOM for bluetape4k-exposed — JetBrains Exposed ORM Kotlin extensions")
                url.set("https://github.com/bluetape4k/bluetape4k-exposed")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("debop")
                        name.set("Sunghyouk Bae")
                        email.set("sunghyouk.bae@gmail.com")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/bluetape4k/bluetape4k-exposed.git")
                    developerConnection.set("scm:git:ssh://github.com/bluetape4k/bluetape4k-exposed.git")
                    url.set("https://github.com/bluetape4k/bluetape4k-exposed")
                }
            }
        }
    }
}

configurePublishingSigning("BluetapeExposed")
