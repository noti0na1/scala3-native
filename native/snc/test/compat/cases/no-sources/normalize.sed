# The JVM lists the -release versions up to the JDK it runs on, snc all of them
s/Choices: 17, 18, .*/Choices: 17/
/^ *2[0-9]\(, 2[0-9]\)*$/d
