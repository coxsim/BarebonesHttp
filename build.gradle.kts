plugins {
    application
    scala
}

application {
    mainClassName = "samples.HelloWorld"
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    compile("org.scala-lang:scala-library:2.12.4")
    testCompile("junit:junit:4.12")
}

repositories {
    jcenter()
}
