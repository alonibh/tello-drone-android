@ECHO OFF
SET DIRNAME=%~dp0
java -classpath "%DIRNAME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
