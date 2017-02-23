/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.workers.internal

import org.gradle.internal.jvm.Jvm
import spock.lang.Unroll

class WorkerExecutorErrorHandlingIntegrationTest extends AbstractWorkerExecutorIntegrationTest {
    @Unroll
    def "produces a sensible error when there is a failure in the #execModel worker runnable"() {
        withRunnableClassInBuildSrc()

        buildFile << """
            $runnableThatFails

            task runInWorker(type: DaemonTask) {
                fork = $doFork
                runnableClass = RunnableThatFails.class
            }
        """.stripIndent()

        when:
        fails("runInWorker")

        then:
        failureHasCause("A failure occurred while executing RunnableThatFails")

        and:
        failureHasCause("Failure from runnable")

        and:
        errorOutput.contains("Caused by: java.lang.RuntimeException: Failure from runnable")

        where:
        doFork | execModel
        true   | 'daemon'
        false  | 'in-process'
    }

    def "produces a sensible error when there is a failure starting a worker daemon"() {
        executer.withStackTraceChecksDisabled()
        withRunnableClassInBuildSrc()

        buildFile << """
            task runInDaemon(type: DaemonTask) {
                fork = true
                additionalForkOptions = {
                    it.jvmArgs "-foo"
                }
            }
        """.stripIndent()

        when:
        fails("runInDaemon")

        then:
        errorOutput.contains(unrecognizedOptionError)

        and:
        failureHasCause("A failure occurred while executing org.gradle.test.TestRunnable")

        and:
        failureHasCause("Failed to run Gradle Worker Daemon")
    }

    @Unroll
    def "produces a sensible error when a parameter can't be serialized to the #execModel worker"() {
        withRunnableClassInBuildSrc()
        withParameterMemberThatFailsSerialization()

        buildFile << """
            $alternateRunnable

            task runAgainInWorker(type: DaemonTask) {
                fork = $doFork
                runnableClass = AlternateRunnable.class
            }
            
            task runInWorker(type: DaemonTask) {
                fork = $doFork
                foo = new FooWithUnserializableBar()
                finalizedBy runAgainInWorker
            }
        """.stripIndent()

        when:
        fails("runInWorker")

        then:
        failureHasCause("A failure occurred while executing org.gradle.test.TestRunnable")
        failureHasCause("Could not serialize parameters")
        failureHasCause("Broken")
        // TODO errorOutput.contains("Caused by: java.io.NotSerializableException: org.gradle.error.Bar")

        and:
        executedAndNotSkipped(":runAgainInWorker")
        assertRunnableExecuted("runAgainInWorker")

        where:
        doFork | execModel
        true   | 'daemon'
        false  | 'in-process'
    }

    @Unroll
    def "produces a sensible error when a parameter can't be de-serialized in the #execModel worker"() {
        def parameterJar = file("parameter.jar")
        withRunnableClassInBuildSrc()
        withParameterMemberThatFailsDeserialization()

        buildFile << """  
            $alternateRunnable

            task runAgainInWorker(type: DaemonTask) {
                fork = $doFork
                runnableClass = AlternateRunnable.class
            }

            task runInWorker(type: DaemonTask) {
                fork = $doFork
                additionalClasspath = files('${parameterJar.name}')
                foo = new FooWithUnserializableBar()
                finalizedBy runAgainInWorker
            }
        """

        when:
        fails("runInWorker")

        then:
        failureHasCause("A failure occurred while executing org.gradle.test.TestRunnable")
        failureHasCause("Could not deserialize parameters")
        failureHasCause("Broken")
        // TODO errorOutput.contains("Caused by: java.lang.ClassNotFoundException: org.gradle.error.Bar")

        and:
        executedAndNotSkipped(":runAgainInWorker")
        assertRunnableExecuted("runAgainInWorker")

        where:
        doFork | execModel
        true   | 'daemon'
        false  | 'in-process'
    }

    @Unroll
    def "produces a sensible error even if the #execModel action failure cannot be fully serialized"() {
        withRunnableClassInBuildSrc()

        buildFile << """
            $alternateRunnable

            task runAgainInWorker(type: DaemonTask) {
                fork = $doFork
                runnableClass = AlternateRunnable.class
            }

            $runnableThatThrowsUnserializableMemberException

            task runInWorker(type: DaemonTask) {
                fork = $doFork
                runnableClass = RunnableThatFails.class
                finalizedBy runAgainInWorker
            }
        """

        when:
        fails("runInWorker")

        then:
        failureHasCause("A failure occurred while executing RunnableThatFails")
        failureHasCause("Unserializable exception from runnable")

        and:
        executedAndNotSkipped(":runAgainInWorker")
        assertRunnableExecuted("runAgainInWorker")

        where:
        doFork | execModel
        true   | 'daemon'
        false  | 'in-process'
    }

    @Unroll
    def "produces a sensible error when the #execModel runnable cannot be instantiated"() {
        withRunnableClassInBuildSrc()

        buildFile << """
            $runnableThatFailsInstantiation

            task runInWorker(type: DaemonTask) {
                fork = $doFork
                runnableClass = RunnableThatFails.class
            }
        """.stripIndent()

        when:
        fails("runInWorker")

        then:
        failureHasCause("A failure occurred while executing RunnableThatFails")
        failureHasCause("You shall not pass!")

        where:
        doFork | execModel
        true   | 'daemon'
        false  | 'in-process'
    }

    @Unroll
    def "produces a sensible error when #execModel parameters are incorrect"() {
        withRunnableClassInBuildSrc()

        buildFile << """
            $runnableWithDifferentConstructor

            task runInWorker(type: DaemonTask) {
                fork = $doFork
                runnableClass = RunnableWithDifferentConstructor.class
            }
        """.stripIndent()

        when:
        fails("runInWorker")

        then:
        failureHasCause("A failure occurred while executing RunnableWithDifferentConstructor")
        failureHasCause("Could not find any public constructor for class RunnableWithDifferentConstructor which accepts parameters")

        where:
        doFork | execModel
        true   | 'daemon'
        false  | 'in-process'
    }

    String getUnrecognizedOptionError() {
        def jvm = Jvm.current()
        if (jvm.ibmJvm) {
            return "Command-line option unrecognised: -foo"
        } else {
            return "Unrecognized option: -foo"
        }
    }

    String getRunnableThatFails() {
        return """
            public class RunnableThatFails implements Runnable {
                public RunnableThatFails(List<String> files, File outputDir, Foo foo) { }

                public void run() {
                    throw new RuntimeException("Failure from runnable");
                }
            }
        """
    }

    String getRunnableThatThrowsUnserializableMemberException() {
        return """
            public class RunnableThatFails implements Runnable {
                public RunnableThatFails(List<String> files, File outputDir, Foo foo) { }

                public void run() {
                    throw new UnserializableMemberException("Unserializable exception from runnable");
                }
                
                private class Bar { }
                
                private class UnserializableMemberException extends RuntimeException {
                    private Bar bar = new Bar();
                    
                    UnserializableMemberException(String message) {
                        super(message);
                    }
                }
            }
        """
    }

    String getClassThatFailsDeserialization() {
        return """
            package org.gradle.other;
            
            import java.io.Serializable;
            import java.io.IOException;
            
            public class Bar implements Serializable {
                private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
                    throw new IOException("Broken");
                }
            }
        """
    }

    String getClassThatFailsSerialization() {
        return """
            package org.gradle.other;
            
            import java.io.Serializable;
            import java.io.IOException;
            
            public class Bar implements Serializable {
                private void writeObject(java.io.ObjectOutputStream out) throws IOException {
                    throw new IOException("Broken");
                }
            }
        """
    }

    String getParameterClassWithUnserializableMember() {
        return """
            package org.gradle.other;
            
            import java.io.Serializable;
            
            public class FooWithUnserializableBar extends Foo implements Serializable {
                private final Bar bar = new Bar();
            }
        """
    }

    String getRunnableThatFailsInstantiation() {
        return """
            public class RunnableThatFails implements Runnable {
                public RunnableThatFails(List<String> files, File outputDir, Foo foo) { 
                    throw new IllegalArgumentException("You shall not pass!")
                }

                public void run() {
                }
            }
        """
    }

    void withParameterMemberThatFailsSerialization() {
        // Create an un-serializable class
        file('buildSrc/src/main/java/org/gradle/other/Bar.java').text = """
            $classThatFailsSerialization
        """

        // Create a Foo class with an un-serializable member
        file('buildSrc/src/main/java/org/gradle/other/FooWithUnserializableBar.java').text = """
            $parameterClassWithUnserializableMember
        """

        addImportToBuildScript("org.gradle.other.FooWithUnserializableBar")
    }

    void withParameterMemberThatFailsDeserialization() {
        // Overwrite the Foo class with a class with an un-serializable member
        file('buildSrc/src/main/java/org/gradle/other/FooWithUnserializableBar.java').text = """
            $parameterClassWithUnserializableMember
        """

        // An unserializable member class
        file('buildSrc/src/main/java/org/gradle/error/Bar.java').text = """
            $classThatFailsDeserialization
        """

        addImportToBuildScript("org.gradle.other.FooWithUnserializableBar")
    }

    String getRunnableWithDifferentConstructor() {
        return """
            public class RunnableWithDifferentConstructor implements Runnable {
                public RunnableWithDifferentConstructor(List<String> files, File outputDir) { 
                }

                public void run() {
                }
            }
        """
    }
}