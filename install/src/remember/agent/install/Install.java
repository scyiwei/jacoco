/*******************************************************************************
 * Copyright (c) 2016 Mountainminds GmbH & Co. KG and Contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Yi Wei - initial API and implementation
 *
 *******************************************************************************/

package remember.agent.install;

/**
 * Created by weiyi on 16/7/17.
 */

import com.sun.tools.attach.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.jar.JarFile;

/**
 * A program which uses the sun.com.tools.attach.VirtualMachine class to install the agent into a
 * running JVM. This provides an alternative to using the -javaagent option to install the agent.
 */
public class Install
{

    private static final String AGENT_LOADED_PROPERTY = "remember.agent.loaded";

    private String agentJar;
    private String id;
    private String props;
    private VirtualMachine vm;
    /**
     * main routine for use from command line
     *
     *
     * Install [-u agentJar] [-Dname=value]* pid
     *
     *
     * see method {@link #usage} for details of the command syntax
     * @param args the command options
     */
    public static void main(String[] args)
    {

        Install attachTest = new Install();
        attachTest.parseArgs(args);
        try {
            attachTest.attach();
            attachTest.injectAgent();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }


    /**
     * @param pid the process id of the JVM into which the agent should be installed or 0 for this JVM
     * @param properties an array of System properties to be installed by the agent with optional values
     * @throws IllegalArgumentException if any of the arguments  is invalid
    * @throws IOException if the jar cannot be opened or uploaded to the requested JVM
     * @throws AttachNotSupportedException if the requested JVM cannot be attached to
     * @throws AgentLoadException if an error occurs during upload of the agent into the JVM
     * @throws AgentInitializationException if the agent fails to initialize after loading. this almost always
     * indicates that the agent is already loaded into the JVM
     */
    public static void install(String pid, String[] properties)
            throws IllegalArgumentException,
            IOException, AttachNotSupportedException,
            AgentLoadException, AgentInitializationException
    {

        for (int i = 0; i < properties.length; i++) {
            String prop = properties[i];
            if (prop == null || prop.length()  == 0) {
                throw new IllegalArgumentException("Install : properties  cannot be null or \"\"");
            }
            if (prop.indexOf(',') >= 0) {
                throw new IllegalArgumentException("Install : properties may not contain ','");
            }
        }

        Install install = new Install(pid, properties);
        install.attach();
        install.injectAgent();
    }

    /**
     * attach to the virtual machine identified by id and return the value of the named property. id must
     * be the id of a virtual machine returned by method availableVMs.
     * @param id the id of the machine to attach to
     * @param property the proeprty to be retrieved
     * @return the value of the property or null if it is not set
     */
    public String getSystemProperty(String id, String property)
    {
        return getProperty(id, property);
    }

    /**
     * attach to the virtual machine identified by id and return {@code true} if
     * agent has already been attached to it. id must be the id of a virtual machine
     * returned by method availableVMs.
     * @param id the id of the machine to attach to
     * @return {@code true} if and only if agent has already been attached to the
     * virtual machine.
     */
    public static boolean isAgentAttached(String id)
    {
        String value = getProperty(id, AGENT_LOADED_PROPERTY);
        return Boolean.parseBoolean(value);
    }

    public void agentAttached(){

    }

    private static String getProperty(String id, String property)
    {
        VirtualMachine vm = null;
        try {
            vm = VirtualMachine.attach(id);
            String value = (String)vm.getSystemProperties().get(property);
            return value;
        } catch (AttachNotSupportedException e) {
            return null;
        } catch (IOException e) {
            return null;
        } finally {
            if (vm != null) {
                try {
                    vm.detach();
                } catch (IOException e) {
                    // ignore;
                }
            }
        }
    }

    /**
     *  only this class creates instances
     */
    private Install()
    {
        agentJar = null;
        id = null;
        props="";
        vm = null;
    }

    /**
     *  only this class creates instances
     */
    private Install(String pid, String[] properties)
    {
        agentJar = null;
        this.id = pid;
        if (properties != null) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < properties.length; i++) {
                builder.append(properties[i]).append(",");
            }
            props = builder.toString();
        } else {
            props = "";
        }
        vm = null;
    }

    /**
     * check the supplied arguments and stash away te relevant data
     * @param args the value supplied to main
     */
    private void parseArgs(String[] args)
    {
        int argCount = args.length;
        int idx = 0;
        if (idx == argCount) {
            usage(0);
        }

        String nextArg = args[idx];

        while (nextArg.length() != 0 &&
                nextArg.charAt(0) == '-') {
            if (nextArg.equals("-u")) {
                idx++;
                if (idx == argCount) {
                    usage(1);
                }
                nextArg = args[idx];
                idx++;
                try {
                    JarFile jarFile = new JarFile(nextArg);
                } catch (IOException e) {
                    System.out.println("Install :" + nextArg + " is not a valid jar file");
                    usage(1);
                }
                agentJar = nextArg;
            } else if (nextArg.equals("-h")) {
                usage(0);
            } else if (nextArg.startsWith("-D")) {
                idx++;
                String prop=nextArg.substring(2);
                if (prop.contains(",")) {
                    System.out.println("Install : invalid property setting " + prop);
                    usage(1);
                }
                props = props + prop+",";
            } else {
                System.out.println("Install : invalid option " + args[idx]);
                usage(1);
            }
            if (idx == argCount) {
                usage(1);
            }
            nextArg = args[idx];
        }

        if (idx != argCount - 1) {
            usage(1);
        }

        // we actually allow any string for the process id as we can look up by name also
        id = nextArg;
    }


    /**
     * attach to the Java process identified by the process id supplied on the command line
     */
    private void attach() throws AttachNotSupportedException, IOException, IllegalArgumentException
    {

        if (id.matches("[0-9]+")) {
            // integer process id
            int pid = Integer.valueOf(id);
            if (pid <= 0) {
                throw new IllegalArgumentException("Install : invalid pid " +id);
            }
            vm = VirtualMachine.attach(Integer.toString(pid));
        } else {
            // try to search for this VM with an exact match
            List<VirtualMachineDescriptor> vmds = VirtualMachine.list();
            for (VirtualMachineDescriptor vmd: vmds) {
                String displayName = vmd.displayName();
                int spacePos = displayName.indexOf(' ');
                if (spacePos > 0) {
                    displayName = displayName.substring(0, spacePos);
                }
                if (displayName.contains(id)) {
                    String pid = vmd.id();
                    vm = VirtualMachine.attach(vmd);
                    return;
                }
            }

            // no match so throw an exception

            throw new IllegalArgumentException("Install : invalid pid " + id);
        }



    }

    /**
     * get the attached process to upload and install the agent jar using whatever agent options were
     * configured on the command line
     */
    private void injectAgent() throws AgentLoadException, AgentInitializationException, IOException
    {
        try {
            int len = props.length();
            if(len>0){
                props = props.substring(0,len-1);
            }
            vm.loadAgent(agentJar, props);
            /*vm.getAgentProperties().setProperty("output","tcpserver");
            vm.getAgentProperties().setProperty("address","localhost");
            vm.getAgentProperties().setProperty("port","8498");*/
        } finally {
            vm.detach();
        }
    }

    /**
     * print usage information and exit with a specific exit code
     * @param exitValue the value to be supplied to the exit call
     */
    private void usage(int exitValue)
    {
        System.out.println("usage : Install [-h host] [-p port] [-b] [-Dprop[=value]]* pid");
        System.out.println("        upload the agent into a running JVM");
        System.out.println("    pid is the process id of the target JVM or the unique name of the process as reported by the jps -l command");
        System.out.println("    -u upload agentJar to running JVM");
        System.out.println("    -Dname=value can be used to set system properties");
        System.out.println("    expects to find agent jar " + agentJar);
        System.exit(exitValue);
    }

}

