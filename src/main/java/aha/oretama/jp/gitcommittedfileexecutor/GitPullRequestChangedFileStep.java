package aha.oretama.jp.gitcommittedfileexecutor;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.google.inject.Inject;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import jenkins.branch.Branch;

import org.jenkinsci.plugins.ghprb.GhprbGitHubAuth;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author aha-oretama
 */
public class GitPullRequestChangedFileStep extends AbstractStepImpl {

    private final String regex;
    private final String testTargetRegex;
    private static final Logger LOGGER = Logger.getLogger(GitPullRequestChangedFileStep.class.getName());

    @DataBoundConstructor
    public GitPullRequestChangedFileStep(String regex, String testTargetRegex) {
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
            return "pullRequestChanged";
        }

        @Override
        public String getDisplayName() {
            return "Get pull request changed files";
        }
    }


    public final static class Execution extends AbstractSynchronousStepExecution<List<String>> {

        @Inject
        private transient GitPullRequestChangedFileStep step;

        @StepContextParameter
        private transient Run<?,?> build;

        @StepContextParameter
        private transient TaskListener listener;

        @Override
        protected List<String> run() throws Exception {
            return getGitPullRequestChangedFiles(build,listener, step.regex, step.testTargetRegex);
        }
    }

    private static final Pattern GITHUB_USER_REPO_PATTERN = Pattern.compile("^(http[s]?://[^/]*)/([^/]*/[^/]*).git$");

    private static List<GhprbGitHubAuth> githubAuth;

    private static String gitHubAuthId = null;

    private static List<String> getGitPullRequestChangedFiles(Run build, TaskListener listener,
        String regex, String testTargetRegex)
        throws IOException, NoSuchFieldException, IllegalAccessException {

        final Job job = build.getParent();

        final BranchJobProperty branchJobProperty =
            (BranchJobProperty) job.getProperty(BranchJobProperty.class);
        Field field = JobProperty.class.getDeclaredField("owner");
        field.setAccessible(true);
        WorkflowJob workflowJob = (WorkflowJob) field.get(branchJobProperty);
        WorkflowMultiBranchProject parent = (WorkflowMultiBranchProject) workflowJob.getParent();
        GitHubSCMSource source = (GitHubSCMSource) parent.getSources().get(0).getSource();
        String apiUrl = source.getApiUri();
        String credentialsId = source.getCredentialsId();
        String reponame = source.getRepoOwner() + "/" + source.getRepository();

        Branch branch = branchJobProperty.getBranch();
//        SCM scm = branch.getScm();
//
//        if(!(scm instanceof GitSCM)) {
//            return new ArrayList<>();
//        }
//        GitSCM gitSCM = (GitSCM) scm;
//
//        UserRemoteConfig userRemoteConfig = gitSCM.getUserRemoteConfigs().get(0);
//        String baseUrl = userRemoteConfig.getUrl();
//        String credentialsId = userRemoteConfig.getCredentialsId();
//
//        Matcher m = GITHUB_USER_REPO_PATTERN.matcher(baseUrl);
//        if(!m.find()) {
//            throw new RuntimeException("");
//        }
//        String apiUrl = m.group(1) + "/api/v3";
//        String reponame = m.group(2);


        GitHub gitHub = null;

        StandardCredentials credentials = lookupCredentials(credentialsId, apiUrl);

        if (credentials == null) {
            LOGGER.log(Level.SEVERE, "Failed to look up credentials for using id: {1}",
                new Object[] { credentialsId });
        } else if (credentials instanceof StandardUsernamePasswordCredentials) {
            LOGGER.log(Level.FINEST, "Using username/password ");
            StandardUsernamePasswordCredentials upCredentials = (StandardUsernamePasswordCredentials) credentials;
            gitHub = GitHub.connectToEnterprise(apiUrl, upCredentials.getUsername(),upCredentials.getPassword().getPlainText());
        } else if (credentials instanceof StringCredentials) {
            LOGGER.log(Level.FINEST, "Using OAuth token");
            StringCredentials tokenCredentials = (StringCredentials) credentials;
            gitHub = GitHub.connectToEnterprise(apiUrl,tokenCredentials.getSecret().getPlainText());
        } else {
            LOGGER.log(Level.SEVERE, "Unknown credential type for using id: {0}: {1}",
                new Object[] { credentialsId, credentials.getClass().getName() });
            return null;
        }

        GHRepository repository = gitHub.getRepository(reponame);

        GHPullRequest pullRequest = repository.getPullRequest(Integer.valueOf(branch.getHead().getName().replace("PR-","")));


        List<String> list = new ArrayList<>();
        list.addAll(pullRequest.listFiles().asList().stream().map(ghPullRequestFileDetail -> ghPullRequestFileDetail.getFilename()).collect(
            Collectors.toList()));

        return list;
    }

    private static GitHub getGithub(Job job) throws IOException {
        GhprbGitHubAuth auth = getGitHubApiAuth();
        return auth.getConnection(job);
    }

    public static GhprbGitHubAuth getGitHubApiAuth() {
        if (gitHubAuthId == null) {
            for (GhprbGitHubAuth auth: getGithubAuth()){
                gitHubAuthId = auth.getId();
//                getDescriptor().save();
                return auth;
            }
        }
        return getGitHubAuth(gitHubAuthId);
    }

    public static GhprbGitHubAuth getGitHubAuth(String gitHubAuthId) {

        if (gitHubAuthId == null) {
            return getGithubAuth().get(0);
        }

        GhprbGitHubAuth firstAuth = null;
        for (GhprbGitHubAuth auth : getGithubAuth()) {
            if (firstAuth == null) {
                firstAuth = auth;
            }
            if (auth.getId().equals(gitHubAuthId)) {
                return auth;
            }
        }
        return firstAuth;
    }

    public static List<GhprbGitHubAuth> getGithubAuth() {
        if (githubAuth == null || githubAuth.size() == 0) {
            githubAuth = new ArrayList<GhprbGitHubAuth>(1);
            githubAuth.add(new GhprbGitHubAuth(null, null, null, "Anonymous connection", null, null));
        }
        return githubAuth;
    }

    public static StandardCredentials lookupCredentials(String credentialId, String uri) {
        LOGGER.log(Level.FINE, "Looking up credentials for {0} for url {1}", new Object[] { credentialId, uri });

        List<StandardCredentials> credentials;

        LOGGER.log(Level.FINE, "Using null context because of issues not getting all credentials");

        credentials = CredentialsProvider.lookupCredentials(StandardCredentials.class, (Item) null, ACL.SYSTEM,
            URIRequirementBuilder.fromUri(uri).build());

        LOGGER.log(Level.FINE, "Found {0} credentials", new Object[]{credentials.size()});

        return (credentialId == null) ? null : CredentialsMatchers.firstOrNull(credentials,
            CredentialsMatchers.withId(credentialId));
    }
}
