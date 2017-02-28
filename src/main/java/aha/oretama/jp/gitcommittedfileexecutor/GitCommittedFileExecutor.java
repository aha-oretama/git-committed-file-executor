package aha.oretama.jp.gitcommittedfileexecutor;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.git.GitChangeSet;
import hudson.plugins.parameterizedtrigger.AbstractBuildParameterFactory;
import hudson.plugins.parameterizedtrigger.AbstractBuildParameters;
import hudson.plugins.parameterizedtrigger.BlockableBuildTriggerConfig;
import hudson.plugins.parameterizedtrigger.BlockingBehaviour;
import hudson.plugins.parameterizedtrigger.TriggerBuilder;
import hudson.scm.ChangeLogSet;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;

import org.apache.commons.io.Charsets;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Sample {@link Builder}.
 *
 * When a build is performed, the {@link #perform} method will be invoked.
 *
 * @author aha-oretama
 */
public class GitCommittedFileExecutor extends Builder {

    private String testJob;
    private String includesPatternFile;
    private String testReportFiles;
    // TODO: To be list.
    private final String regex;
    private final String testTargetRegex;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public GitCommittedFileExecutor(String testJob, String includesPatternFile, String testReportFiles, String regex, String testTargetRegex) {
        this.testJob = testJob;
        this.includesPatternFile = includesPatternFile;
        this.testReportFiles = testReportFiles;
        this.regex = regex;
        this.testTargetRegex = testTargetRegex;
    }

    public String getTestJob() {
        return testJob;
    }

    public String getIncludesPatternFile() {
        return includesPatternFile;
    }

    public String getTestReportFiles() {
        return testReportFiles;
    }

    public String getRegex() {
        return regex;
    }

    public String getTestTargetRegex() {
        return testTargetRegex;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
        ChangeLogSet<? extends ChangeLogSet.Entry> changeSet = build.getChangeSet();

        // normal exit if there are no changed files.
        if(changeSet.getItems().length == 0) {
            return false;
        }

        if (!changeSet.getKind().equals("git")) {
            throw new AbortException("There is other than updates by git. This plugin supports git update only.");
        }

        FilePath dir = build.getWorkspace().child("test-target");
        dir.deleteRecursive();

        for (String targetRegex :getExpressionsForTestInclusion(build, listener, regex, testTargetRegex) ) {
            try (OutputStream outputStream = dir.child(includesPatternFile).write()) {
                OutputStreamWriter streamWriter =
                    new OutputStreamWriter(outputStream, Charsets.UTF_8);
                PrintWriter printWriter = new PrintWriter(streamWriter);

                // TODO: modify multi group value
                printWriter.println(targetRegex);
            }
        }

        createTriggerBuilder().perform(build,launcher,listener);

        return true;
    }

    static List<String> getExpressionsForTestInclusion(Run build, TaskListener listener, String regex, String testTargetRegex) {

        List<ChangeLogSet<? extends ChangeLogSet.Entry>> changeItems = null;

        if(build instanceof WorkflowRun) {
            changeItems = ((WorkflowRun) build).getChangeSets();
        }else if(build instanceof AbstractBuild) {
            changeItems = ((AbstractBuild) build).getChangeSets();
        }else {
            return new ArrayList<>();
        }

        List<GitChangeSet> gitChangeSets = new ArrayList<>();
        for (ChangeLogSet changeLogSet :changeItems) {
            if(changeLogSet.getKind().equals("git")){
                gitChangeSets.addAll(Stream.of(changeLogSet.getItems()).map(item -> (GitChangeSet)item).collect(Collectors.toList()));
            }
        }

        List<String> paths = new ArrayList<>();
        for (GitChangeSet gitChangeSet: gitChangeSets) {
            paths.addAll(gitChangeSet.getAffectedPaths());
        }

        Pattern pattern = Pattern.compile(regex);
        List<String> expressions = new ArrayList<>();
        for (String path: paths) {
            listener.getLogger().println("path: " + path);

            Matcher matcher = pattern.matcher(path);
            if(matcher.find() && matcher.groupCount() >= 1) {
                listener.getLogger().println("matched: " + matcher.group(1));
                // TODO: fix regex.
                expressions.add("**/" + matcher.group(1) + ".*");
            }
        }

        return expressions;
    }

    /**
     * Create {@link hudson.plugins.parameterizedtrigger.TriggerBuilder} for launching test jobs.
     */
    private TriggerBuilder createTriggerBuilder() {
        // to let the caller job do a clean up, don't let the failure in the test job early-terminate the build process
        // that's why the first argument is ABORTED.
        BlockingBehaviour blocking = new BlockingBehaviour(Result.ABORTED, Result.UNSTABLE, Result.FAILURE);
        final AtomicInteger iota = new AtomicInteger(0);

        List<AbstractBuildParameters> parameterList = new ArrayList<>();
        parameterList.add(
            // put a marker action that we look for to collect test reports
            new AbstractBuildParameters() {
                @Override
                public Action getAction(AbstractBuild<?, ?> build, TaskListener listener) throws IOException, InterruptedException, DontTriggerException {
                    return new TestCollector(build, GitCommittedFileExecutor.this, iota.incrementAndGet());
                }
            });

        // actual logic of child process triggering is left up to the parameterized build
        List<MultipleBinaryFileParameterFactory.ParameterBinding> parameterBindings = new ArrayList<>();
        parameterBindings.add(new MultipleBinaryFileParameterFactory.ParameterBinding(getIncludesPatternFile(), "test-targets/" + includesPatternFile));
        MultipleBinaryFileParameterFactory factory = new MultipleBinaryFileParameterFactory(parameterBindings);
        BlockableBuildTriggerConfig config = new BlockableBuildTriggerConfig(
            testJob,
            blocking,
            Collections.<AbstractBuildParameterFactory>singletonList(factory),
            parameterList
        );

        return new TriggerBuilder(config);
    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link GitCommittedFileExecutor}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     * <p>
     * See {@code src/main/resources/hudson/plugins/hello_world/GitCommittedFileExecutor/*.jelly}
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        /**
         * To persist global configuration information,
         * simply store it in a field and call save().
         *
         * <p>
         * If you don't want fields to be persisted, use {@code transient}.
         */
        private boolean useFrench;

        /**
         * In order to load the persisted global configuration, you have to 
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types 
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Changed File Test Executor";
        }
    }
}

