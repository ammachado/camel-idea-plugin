/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.cameltooling.idea.runner.debugger;

import com.github.cameltooling.idea.runner.debugger.stack.CamelMessageInfo;
import com.github.cameltooling.idea.service.CamelService;
import com.intellij.debugger.DebuggerManagerEx;
import com.intellij.debugger.DefaultDebugEnvironment;
import com.intellij.debugger.engine.DebugProcessImpl;
import com.intellij.debugger.engine.JavaDebugProcess;
import com.intellij.debugger.impl.DebuggerSession;
import com.intellij.debugger.impl.GenericDebuggerRunner;
import com.intellij.debugger.ui.tree.render.BatchEvaluator;
import com.intellij.execution.DefaultExecutionResult;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.configurations.RemoteConnection;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunConfigurationBase;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugProcessStarter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.impl.XDebugSessionImpl;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.maven.execution.MavenRunConfiguration;
import org.jetbrains.idea.maven.model.MavenArtifact;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectsManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class CamelDebuggerRunner extends GenericDebuggerRunner {
    private static final Logger LOG = Logger.getInstance(CamelDebuggerRunner.class);

    private static final String MIN_CAMEL_VERSION = "3.15.0-SNAPSHOT";

    public static final String JAVA_CONTEXT = "Java";
    public static final String CAMEL_CONTEXT = "Camel";

    @NonNls
    private static final String ID = "CamelDebuggerRunner";

    public CamelDebuggerRunner() {
        super();
    }

    @NotNull
    @Override
    public String getRunnerId() {
        return ID;
    }

    @Override
    public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
        if (profile instanceof RunConfigurationBase) {
            try {
                final RunConfigurationBase base = (RunConfigurationBase) profile;
                final Project project = base.getProject();
                final CamelService camelService = project.getService(CamelService.class);
                if (camelService != null) {
                    boolean isDebug = executorId.equals(DefaultDebugExecutor.EXECUTOR_ID);
                    boolean isCamelPresent = camelService.isCamelPresent();
                    boolean canRun = isDebug && isCamelPresent;
                    LOG.debug("Executor ID is " + executorId + " ; Camel present = " + camelService.isCamelPresent() + " ; canRun is " + canRun);
                    return canRun;
                }
            } catch (Exception e) {
                LOG.debug("Camel Debugger cannot run", e);
                return false;
            }
        }
        LOG.debug("Camel Debugger cannot run, profile is not RunConfiguration");
        return false;
    }

    @Override
    public void execute(@NotNull ExecutionEnvironment environment) throws ExecutionException {
        checkConfiguration(environment.getRunnerAndConfigurationSettings().getConfiguration());
        super.execute(environment);
    }

    @Override
    @Nullable
    protected RunContentDescriptor attachVirtualMachine(final RunProfileState state, final @NotNull ExecutionEnvironment env, RemoteConnection connection, boolean pollConnection)
            throws ExecutionException {
        AtomicReference<ExecutionException> ex = new AtomicReference<>();
        AtomicReference<RunContentDescriptor> result = new AtomicReference<>();

        ApplicationManager.getApplication().invokeAndWait(() -> {
            try {
                result.set(attachVM(state, env, connection, pollConnection));
            } catch (ExecutionException e) {
                ex.set(e);
            }
        });
        if (ex.get() != null) {
            throw ex.get();
        }
        return result.get();
    }

    private RunContentDescriptor attachVM(final RunProfileState state, final @NotNull ExecutionEnvironment env, RemoteConnection connection, boolean pollConnection)
            throws ExecutionException {
        LOG.debug("Attaching VM...");
        DefaultDebugEnvironment environment = new DefaultDebugEnvironment(env, state, connection, pollConnection);
        final DebuggerSession debuggerSession = DebuggerManagerEx.getInstanceEx(env.getProject()).attachVirtualMachine(environment);
        final CamelDebuggerSession camelDebuggerSession = new CamelDebuggerSession();

        if (debuggerSession == null) {
            return null;
        } else {
            final DebugProcessImpl debugProcess = debuggerSession.getProcess();
            if (!debugProcess.isDetached() && !debugProcess.isDetaching()) {
                if (environment.isRemote()) {
                    debugProcess.putUserData(BatchEvaluator.REMOTE_SESSION_KEY, Boolean.TRUE);
                }

                return XDebuggerManager.getInstance(env.getProject()).startSession(env, new XDebugProcessStarter() {
                    @NotNull
                    public XDebugProcess start(@NotNull XDebugSession session) {

                        final XDebugSessionImpl sessionImpl = (XDebugSessionImpl) session;
                        final ExecutionResult executionResult = debugProcess.getExecutionResult();
                        final Map<String, XDebugProcess> context = new HashMap<>();
                        final ContextAwareDebugProcess contextAwareDebugProcess = new ContextAwareDebugProcess(session, executionResult.getProcessHandler(), context, JAVA_CONTEXT);

                        camelDebuggerSession.addMessageReceivedListener(new MessageReceivedListener() {
                            @Override
                            public void onNewMessageReceived(CamelMessageInfo camelMessageInfo) {
                                contextAwareDebugProcess.setContext(CAMEL_CONTEXT);
                            }
/*
                            @Override
                            public void onExceptionThrown(CamelMessageInfo camelMessageInfo, ObjectFieldDefinition exceptionThrown) {
                                contextAwareDebugProcess.setContext(CAMEL_CONTEXT);
                            }

                            @Override
                            public void onExecutionStopped(CamelMessageInfo camelMessageInfo, List<ObjectFieldDefinition> frame, String path, String internalPosition) {
                                contextAwareDebugProcess.setContext(CAMEL_CONTEXT);
                            }
*/
                        });

                        debuggerSession.getContextManager().addListener((newContext, event) -> contextAwareDebugProcess.setContext(JAVA_CONTEXT));

                        //Init Java Debug Process
                        sessionImpl.addExtraActions(executionResult.getActions());
                        if (executionResult instanceof DefaultExecutionResult) {
                            sessionImpl.addRestartActions(((DefaultExecutionResult) executionResult).getRestartActions());
                            //sessionImpl.addExtraStopActions(((DefaultExecutionResult) executionResult).getAdditionalStopActions());
                        }
                        final JavaDebugProcess javaDebugProcess = JavaDebugProcess.create(session, debuggerSession);

                        //Init Camel Debug Process
                        final CamelDebugProcess camelDebugProcess = new CamelDebugProcess(session, camelDebuggerSession, javaDebugProcess.getProcessHandler());

                        //Register All Processes
                        context.put(JAVA_CONTEXT, javaDebugProcess);
                        context.put(CAMEL_CONTEXT, camelDebugProcess);
                        return contextAwareDebugProcess;
                    }
                }).getRunContentDescriptor();
            } else {
                debuggerSession.dispose();
                camelDebuggerSession.dispose();
                return null;
            }
        }
    }

    private void checkConfiguration(RunConfiguration configuration) {
        if (configuration instanceof MavenRunConfiguration) {
            MavenRunConfiguration mavenConfiguration = (MavenRunConfiguration) configuration;
            final VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(mavenConfiguration.getRunnerParameters().getWorkingDirPath() + "/pom.xml");
            if (virtualFile != null) {
                MavenProject mavenProject = MavenProjectsManager.getInstance(configuration.getProject()).findProject(virtualFile);
                if (mavenProject != null) {
                    //Find Camel Version
                    List<MavenArtifact> dependencies = mavenProject.getDependencies();
                    MavenArtifact camelArtifact = dependencies.stream().filter(mavenArtifact -> mavenArtifact.getArtifactId().equals("camel-main")
                            || mavenArtifact.getArtifactId().equals("camel-spring-boot")).findFirst().orElse(null);
                    if (camelArtifact != null) {
                        String camelVersion = camelArtifact.getBaseVersion();
                        ComparableVersion version = new ComparableVersion(camelVersion);
                        if (version.compareTo(new ComparableVersion(MIN_CAMEL_VERSION)) < 0) { //This is an older version of Camel, debugger is not supported
                            NotificationGroupManager.getInstance()
                                    .getNotificationGroup("Debugger messages")
                                    .createNotification("Camel version is " + version.toString() + " ; minimum required version for debugger is 3.15.0",
                                            MessageType.WARNING).notify(mavenConfiguration.getProject());
                        }
                    }
                    MavenArtifact camelDebugArtifact = dependencies.stream().filter(mavenArtifact -> mavenArtifact.getArtifactId().equals("camel-debug")
                            || mavenArtifact.getArtifactId().equals("camel-debug-starter")).findFirst().orElse(null);
                    if (camelDebugArtifact == null) {
                        NotificationGroupManager.getInstance()
                                .getNotificationGroup("Debugger messages")
                                .createNotification("Camel Debugger is not found in classpath!\nPlease add \"camel-debug\" or \"camel-debug-starter\" artifact to your project classpath and/or dependencies.",
                                        MessageType.WARNING).notify(mavenConfiguration.getProject());
                    }
                }
            }
        }
    }
}