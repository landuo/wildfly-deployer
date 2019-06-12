package org.jenkinsci.plugins.wildfly;

import hudson.Extension;
import hudson.FilePath;
import hudson.FilePath.FileCallable;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.TopLevelItem;
import hudson.remoting.VirtualChannel;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jboss.dmr.ModelNode;
import org.jenkinsci.remoting.RoleChecker;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.servlet.ServletException;
import java.io.File;
import java.io.IOException;

/**
 * @author Dan B. Dillingham
 */
public class WildflyBuilderCustom extends Builder {

    private final String war;
    private final String host;
    private final String port;
    private final String username;
    private final String password;
    private final String server;
    private final int timeout=30000;

    @DataBoundConstructor
    public WildflyBuilderCustom(String war, String host, String port, String username, String password, String server) {
        this.war = war;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.server = server;
    }

    public String getWar() {
        return war;
    }

    public String getHost() {
        return host;
    }

    public String getPort() {
        return port;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getServer() {
        return server;
    }

    public int getTimeout() {
        return timeout;
    }


    @Override public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {

        char[] passwordAsCharArray;
        CLI.Result result;
        String warPath, warFilename, response, localPath, remotePath;
        FilePath localFP = null;

        try {
            int portAsInt = Integer.parseInt(port);

            FilePath fp = new FilePath(build.getWorkspace(), war);
            remotePath = fp.getRemote();
            if (!fp.exists()) {
                listener.fatalError("The '" + war + "' file does not exist in workspace.");
                return false;
            }

            // if running on a remote slave, copy the WAR file to the master so CLI (which must run on the master) can access it
            if (fp.isRemote()) {
                localPath = Jenkins.getInstance().getWorkspaceFor((TopLevelItem)build.getProject().getRootProject())
                    .getRemote();
                localPath = localPath.concat("/" + war);
                localFP = new FilePath(new File(localPath));
                fp.act(new GrabWARFile(localFP));
                warPath = localPath;
            } else
                warPath = remotePath;

            CLI cli = CLI.newInstance();
            if (username.length() > 0) {
                passwordAsCharArray = password.toCharArray();
                cli.connect(host, portAsInt, username, passwordAsCharArray,timeout);
            } else {
                cli.connect(host, portAsInt, null, null);
            }

            listener.getLogger().println("Connected to WildFly at " + host + ":" + port);

            int idx = war.lastIndexOf("/");
            if (idx > 0) {
                warFilename = war.substring(idx + 1, war.length());
            } else {
                warFilename = war;
            }

            // if application exists, undeploy it first...
            if (applicationExists(cli, warFilename, server)) {
                listener.getLogger().println("Application " + warFilename + " exists, undeploying...");
                if (server.length() > 0) {
                    result = cli.cmd("undeploy " + warFilename + " --server-groups=" + server);
                } else {
                    result = cli.cmd("undeploy " + warFilename);
                }
                response = getWildFlyResponse(result);
                if (response.indexOf("{\"outcome\" => \"failed\"") >= 0) {
                    listener.fatalError(response);
                    return false;
                } else {
                    listener.getLogger().println(response + "\n reload server...");
                    CLI.Result reloadResult = cli.cmd("reload");
                    response = getWildFlyResponse(reloadResult);
                    if (response.indexOf("{\"outcome\" => \"failed\"") >= 0) {
                        listener.fatalError(response);
                        return false;
                    } else {
                        try {
                            cli.disconnect();
                            listener.getLogger().println(response);
                            Thread.currentThread().sleep(20000);
                            cli.connect(host, portAsInt, username, password.toCharArray(),timeout);
                        } catch (Exception e) {
                            listener.getLogger().println(e.getMessage());
                        }
                    }

                }
            }

            listener.getLogger().println("Deploying " + warFilename + " ...");
            if (server.length() > 0) {
                result = cli.cmd("deploy " + warPath + " --server-groups=" + server);
            } else {
                result = cli.cmd("deploy " + warPath);
            }

            response = getWildFlyResponse(result);
            if (response.indexOf("{\"outcome\" => \"failed\"") >= 0) {
                listener.fatalError(response);
                return false;
            } else {
                listener.getLogger().println(response);
            }

            cli.disconnect();

            if (localFP != null) {
                localFP.delete();
            }

        } catch (Exception e) {
            e.printStackTrace();
            listener.fatalError(e.getMessage());
            return false;
        }

        return true;

    }

    private boolean applicationExists(CLI cli, String war, String server) {

        CLI.Result result;
        String response;

        if (server.length() > 0) {
            result = cli.cmd("deployment-info --server-group=" + server);
        } else {
            result = cli.cmd("deployment-info");
        }

        response = getWildFlyResponse(result);

        if (response.indexOf(war) < 0)
            return false;
        else
            return true;
    }

    private String getWildFlyResponse(CLI.Result result) {

        ModelNode modelNode = result.getResponse();
        String response = modelNode.asString();

        return response;
    }

    @Override public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    private static final class GrabWARFile implements FileCallable<Void> {
        private static final long serialVersionUID = 1;

        private final FilePath fp;

        public GrabWARFile(FilePath fp) {
            this.fp = fp;
        }

        @Override public Void invoke(File f, VirtualChannel channel) {

            try {
                FilePath remoteFilePath = new FilePath(f);
                fp.copyFrom(remoteFilePath);
            } catch (Exception e) {
                return null;
            }

            return null;
        }

        @Override public void checkRoles(RoleChecker checker) throws SecurityException {

        }

    }

    @Extension public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public DescriptorImpl() {
            load();
        }

        public FormValidation doCheckWar(@QueryParameter String value) throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please specify a WAR or EAR filename.");
            if (!value.toLowerCase().contains(".war".toLowerCase()) && !value.toLowerCase()
                .contains(".ear".toLowerCase()))
                return FormValidation.error("Please specify a valid WAR or EAR filename.");
            if (value.length() < 7)
                return FormValidation.warning("Is that a valid WAR or EAR filename?");
            return FormValidation.ok();
        }

        public FormValidation doCheckHost(@QueryParameter String value) throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please specify a valid hostname.");
            if (value.length() < 4)
                return FormValidation.warning("Is that a valid hostname?");
            return FormValidation.ok();
        }

        public FormValidation doCheckPort(@QueryParameter String value) throws IOException, ServletException {
            if (value.length() == 0 || value.length() < 4 || value.length() > 5 || !value.matches("[0-9]+"))
                return FormValidation.error("Please specify a valid port number.");
            return FormValidation.ok();
        }

        public FormValidation doCheckPassword(@QueryParameter String value, @QueryParameter String username)
            throws IOException, ServletException {
            if (value.length() != 0)
                if (username.length() == 0)
                    return FormValidation.error("Both a user name and a password must be specified.");
            return FormValidation.ok();
        }

        public FormValidation doCheckUsername(@QueryParameter String value, @QueryParameter String password)
            throws IOException, ServletException {
            if (value.length() != 0)
                if (password.length() == 0)
                    return FormValidation.error("Both a user name and a password must be specified.");
            return FormValidation.ok();
        }

        public FormValidation doCheckServer(@QueryParameter String value) throws IOException, ServletException {
            if (value.length() > 0 && value.length() < 5)
                return FormValidation.warning("Is that a valid server group or standalone server name?");
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        public String getDisplayName() {
            return "Deploy WAR/EAR to WildFly For Accentrix";
        }

        @Override public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            save();
            return super.configure(req, formData);
        }

    }

}

