package org.jenkinsci.plugins.wildfly;

import org.jboss.as.cli.*;
import org.jboss.dmr.ModelNode;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

public class CLI {

    private CommandContext ctx;

    private CLI() {} // only allow new instances from newInstance() method.

    /**
     * Create a new CLI instance.
     *
     * @return The CLI instance.
     */
    public static CLI newInstance() {
        return new CLI();
    }

    private void checkAlreadyConnected() {
        if (ctx != null) throw new IllegalStateException("Already connected to server.");
    }

    private void checkNotConnected() {
        if (ctx == null) throw new IllegalStateException("Not connected to server.");
        if (ctx.isTerminated()) throw new IllegalStateException("Session is terminated.");
    }

    /**
     * Return the CLI CommandContext that was created when connected to the server.  This allows a script developer full
     * access to CLI facilities if needed.
     *
     * @return The CommandContext, or <code>null</code> if not connected.
     */
    public CommandContext getCommandContext() {
        return this.ctx;
    }

    /**
     * Connect to the server using the default host and port.
     */
    public void connect() {
        checkAlreadyConnected();
        try {
            ctx = CommandContextFactory.getInstance().newCommandContext();
            ctx.connectController();
        } catch (CliInitializationException e) {
            throw new IllegalStateException("Unable to initialize command context.", e);
        } catch (CommandLineException e) {
            throw new IllegalStateException("Unable to connect to controller.", e);
        }
    }

    /**
     * Connect to the server using the default host and port.
     *
     * @param username The user name for logging in.
     * @param password The password for logging in.
     */
    public void connect(String username, char[] password) {
        checkAlreadyConnected();
        try {
            ctx = CommandContextFactory.getInstance().newCommandContext(username, password);
            ctx.connectController();
        } catch (CliInitializationException e) {
            throw new IllegalStateException("Unable to initialize command context.", e);
        } catch (CommandLineException e) {
            throw new IllegalStateException("Unable to connect to controller.", e);
        }
    }

    public void connect(String controller, String username, char[] password) {
        checkAlreadyConnected();
        try {
            ctx = CommandContextFactory.getInstance().newCommandContext(controller, username, password);
            ctx.connectController();
        } catch (CliInitializationException e) {
            throw new IllegalStateException("Unable to initialize command context.", e);
        } catch (CommandLineException e) {
            throw new IllegalStateException("Unable to connect to controller.", e);
        }
    }

    public void connect(String controllerHost, int controllerPort, String username, char[] password) {
        connect("http-remoting", controllerHost, controllerPort, username, password);
    }

    public void connect(String controllerHost, int controllerPort, String username, char[] password,int timeout) {
        connect("http-remoting", controllerHost, controllerPort, username, password, timeout);
    }

    public void connect(String protocol, String controllerHost, int controllerPort, String username, char[] password) {
        checkAlreadyConnected();
        try {
            ctx = CommandContextFactory.getInstance().newCommandContext(constructUri(protocol, controllerHost, controllerPort), username, password);
            ctx.connectController();
        } catch (CliInitializationException e) {
            throw new IllegalStateException("Unable to initialize command context.", e);
        } catch (CommandLineException e) {
            throw new IllegalStateException("Unable to connect to controller.", e);
        }
    }

    public void connect(String protocol, String controllerHost, int controllerPort, String username, char[] password,int timeout) {
        checkAlreadyConnected();
        try {
            ctx = CommandContextFactory.getInstance().newCommandContext(
                constructUri(protocol, controllerHost, controllerPort), username, password,false,timeout);
            ctx.connectController();
        } catch (CliInitializationException e) {
            throw new IllegalStateException("Unable to initialize command context.", e);
        } catch (CommandLineException e) {
            throw new IllegalStateException("Unable to connect to controller.", e);
        }
    }

    /**
     * Disconnect from the server.
     */
    public void disconnect() {
        try {
            checkNotConnected();
            ctx.terminateSession();
        } finally {
            ctx = null;
        }
    }

    public CLI.Result cmd(String cliCommand) {
        checkNotConnected();
        try {
            ModelNode request = ctx.buildRequest(cliCommand);
            ModelNode response = ctx.getModelControllerClient().execute(request);
            return new CLI.Result(cliCommand, request, response);
        } catch (CommandFormatException cfe) {
            // if the command can not be converted to a ModelNode, it might be a local command
            try {
                ctx.handle(cliCommand);
                return new CLI.Result(cliCommand, ctx.getExitCode());
            } catch (CommandLineException cle) {
                throw new IllegalArgumentException("Error handling command: " + cliCommand, cle);
            }
        } catch (IOException ioe) {
            throw new IllegalStateException("Unable to send command " + cliCommand + " to server.", ioe);
        }
    }

    private String constructUri(final String protocol, final String host, final int port) {
        try {
            URI uri = new URI(protocol, null, host, port, null, null, null);
            // String the leading '//' if there is no protocol.
            return protocol == null ? uri.toString().substring(2) : uri.toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Unable to construct URI.", e);
        }
    }

    /**
     * The Result class provides all information about an executed CLI command.
     */
    public class Result {
        private String cliCommand;
        private ModelNode request;
        private ModelNode response;

        private boolean isSuccess = false;
        private boolean isLocalCommand = false;

        Result(String cliCommand, ModelNode request, ModelNode response) {
            this.cliCommand = cliCommand;
            this.request = request;
            this.response = response;
            this.isSuccess = response.get("outcome").asString().equals("success");
        }

        Result(String cliCommand, int exitCode) {
            this.cliCommand = cliCommand;
            this.isSuccess = exitCode == 0;
            this.isLocalCommand = true;
        }

        /**
         * Return the original command as a String.
         * @return The original CLI command.
         */
        public String getCliCommand() {
            return this.cliCommand;
        }

        /**
         * If the command resulted in a server-side operation, return the ModelNode representation of the operation.
         *
         * @return The request as a ModelNode, or <code>null</code> if this was a local command.
         */
        public ModelNode getRequest() {
            return this.request;
        }

        /**
         * If the command resulted in a server-side operation, return the ModelNode representation of the response.
         *
         * @return The server response as a ModelNode, or <code>null</code> if this was a local command.
         */
        public ModelNode getResponse() {
            return this.response;
        }

        /**
         * Return true if the command was successful.  For a server-side operation, this is determined by the outcome of the
         * operation on the server side.
         *
         * @return <code>true</code> if the command was successful, <code>false</code> otherwise.
         */
        public boolean isSuccess() {
            return this.isSuccess;
        }

        /**
         * Return true if the command was only executed locally and did not result in a server-side operation.
         *
         * @return <code>true</code> if the command was only executed locally, <code>false</code> otherwise.
         */
        public boolean isLocalCommand() {
            return this.isLocalCommand;
        }
    }
}
