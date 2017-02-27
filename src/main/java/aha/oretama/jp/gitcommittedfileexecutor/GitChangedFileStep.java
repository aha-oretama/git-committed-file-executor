package aha.oretama.jp.gitcommittedfileexecutor;

import com.google.inject.Inject;
import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.model.Run;
import hudson.model.TaskListener;

import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.List;

/**
 * @author aha-oretama
 */
public class GitChangedFileStep extends AbstractStepImpl {

    private final String regex;
    private final String testTargetRegex;

    @DataBoundConstructor
    public GitChangedFileStep(String regex, String testTargetRegex) {
        this.regex = regex;
        this.testTargetRegex = testTargetRegex;
    }

    @Extension
    public static final class DescriptorImpl extends AbstractStepDescriptorImpl {

        public DescriptorImpl() {
            super(Execution.class);
        }

        @Override
        public String getFunctionName() {
            return "gitChangedFiles";
        }

        @Override
        public String getDisplayName() {
            return "Get git changed files";
        }
    }


    public static final class Execution extends AbstractSynchronousStepExecution<List<String>> {

        @Inject
        private transient GitChangedFileStep step;

        @StepContextParameter
        private transient Run<?,?> build;

        @StepContextParameter
        private transient TaskListener listener;

        @Override
        protected List<String> run() throws Exception {
            return GitCommittedFileExecutor.getExpressionsForTestInclusion((AbstractBuild<?, ?>) build,listener, step.regex, step.testTargetRegex);
        }
    }
}
