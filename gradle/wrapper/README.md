# Gradle Wrapper GE360

GE360 usa Gradle 8.9, compatibile con Android Gradle Plugin 8.7.3.

I file `gradlew` e `gradlew.bat` eseguono un bootstrap verificato: se il piccolo Wrapper JAR non è ancora presente, viene scaricato dal dominio ufficiale Gradle dentro `.gradle/wrapper-bootstrap/` e accettato solo se SHA-256 = `498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17`.

La distribuzione `gradle-8.9-bin.zip` è a sua volta fissata in `gradle-wrapper.properties` con SHA-256 `d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab`.

Questo bootstrap non compila automaticamente l'app e non avvia GitHub Actions. La prima build reale va eseguita separatamente e verificata.
