/*
 * Copyright 2016 Javier Garcia Alonso.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.profesorfalken.jpowershell;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Allows to open a session into PowerShell console and launch different
 * commands.<br>
 * This class cannot be directly instantiated. Instead, use the method
 * PowerShell.openSession and call the commands using the returned instance.
 * <p>
 * Once the session is finished, call close() method in order to free the
 * resources.
 *
 * @author Javier Garcia Alonso
 */
public class PowerShell {

    //Process to store PowerShell session

    private Process p;
    //Writer to send commands
    private PrintWriter commandWriter;

    //Threaded session variables
    private boolean closed = false;
    private ExecutorService threadpool;
    
    //Config values
    private int maxThreads = 3; 
    private int waitPause = 10;
    private long maxWait = 10000;

    //Private constructor.
    private PowerShell() {
    }
    
    /**
     * Allows to override jPowerShell configuration using a map of key/value <br>
     * Default values are taken from file <i>jpowershell.properties</i>, which can be 
     * replaced just setting it on project classpath
     * 
     * The values that can be overridden are:
     * <ul>
     * <li>maxThreads: the maximum number of thread to use in pool. 3 is an optimal and default value</li>
     * <li>waitPause: the pause in ms between each loop pooling for a response. Default value is 10</li>
     * <li>maxWait: the maximum wait in ms for the command to execute. Default value is 10000</li>
     * </ul>
     * 
     * @param config map with the configuration in key/value format
     * @return instance to chain
     */
    public PowerShell configuration(Map<String, String> config) {
        try {
            this.maxThreads = Integer.valueOf((config != null && config.get("maxThreads") != null) ? config.get("maxThreads") : PowerShellConfig.getConfig().getProperty("maxThreads"));
            this.waitPause = Integer.valueOf((config != null && config.get("waitPause") != null) ? config.get("waitPause") : PowerShellConfig.getConfig().getProperty("waitPause"));
            this.maxWait = Long.valueOf((config != null && config.get("maxWait") != null) ? config.get("maxWait") : PowerShellConfig.getConfig().getProperty("maxWait"));
        } catch (NumberFormatException nfe) {
            Logger.getLogger(PowerShell.class.getName()).log(Level.SEVERE, "Could not read configuration. Use default values.", nfe);
        }
        return this;
    }

    //Initializes PowerShell console in which we will enter the commands
    private PowerShell initalize() throws PowerShellNotAvailableException {            
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoExit", "-Command", "-");
        try {
            p = pb.start();
        } catch (IOException ex) {
            throw new PowerShellNotAvailableException(
                    "Cannot execute PowerShell.exe. Please make sure that it is installed in your system", ex);
        }

        commandWriter
                = new PrintWriter(new OutputStreamWriter(new BufferedOutputStream(p.getOutputStream())), true);

        //Init thread pool
        this.threadpool = Executors.newFixedThreadPool(this.maxThreads);

        return this;
    }

    /**
     * Creates a session in PowerShell console an returns an instance which
     * allows to execute commands in PowerShell context
     *
     * @return an instance of the class
     * @throws PowerShellNotAvailableException if PowerShell is not installed in
     * the system
     */
    public static PowerShell openSession() throws PowerShellNotAvailableException {
        PowerShell powerShell = new PowerShell();
        
        //Start with default configuration
        powerShell.configuration(null);

        return powerShell.initalize();
    }

    /**
     * Launch a PowerShell command.<p>
     * This method launch a thread which will be executed in the already created
     * PowerShell console context
     *
     * @param command the command to call. Ex: dir
     * @return PowerShellResponse the information returned by powerShell
     */
    public PowerShellResponse executeCommand(String command) {
        Callable<String> commandProcessor = new PowerShellCommandProcessor("standard", 
                p.getInputStream(), this.maxWait, this.waitPause);
        Callable<String> commandProcessorError = new PowerShellCommandProcessor("error", 
                p.getErrorStream(), this.maxWait, this.waitPause);

        String commandOutput = "";
        boolean isError = false;
        boolean timeout = false;

        Future<String> result = threadpool.submit(commandProcessor);
        Future<String> resultError = threadpool.submit(commandProcessorError);

        //Launch command
        commandWriter.println(command);

        try {
            while (!result.isDone() && !resultError.isDone()) {
                Thread.sleep(this.waitPause);
            }
            if (result.isDone()) {
                if (((PowerShellCommandProcessor)commandProcessor).isTimeout()) {
                    timeout = true;
                } else {
                    commandOutput = result.get();                
                }
            } else {
                isError = true;
                commandOutput = resultError.get();
            }
        } catch (InterruptedException ex) {
            Logger.getLogger(PowerShell.class.getName()).log(Level.SEVERE, "Unexpected error when processing PowerShell command", ex);
        } catch (ExecutionException ex) {
            Logger.getLogger(PowerShell.class.getName()).log(Level.SEVERE, "Unexpected error when processing PowerShell command", ex);
        } finally {
            //issue #2. Close and cancel processors/threads - Thanks to r4lly for helping me here
            ((PowerShellCommandProcessor) commandProcessor).close();
            ((PowerShellCommandProcessor) commandProcessorError).close();
        }

        return new PowerShellResponse(isError, commandOutput, timeout);
    }

    /**
     * Closes all the resources used to maintain the PowerShell context
     */
    public void close() {
        if (!this.closed) {
            try {
                Future<String> closeTask = threadpool.submit(new Callable<String>() {
                    public String call() throws Exception {
                        commandWriter.println("exit");
                        p.waitFor();
                        return "OK";
                    }
                });
                waitUntilClose(closeTask);
            } catch (InterruptedException ex) {
                Logger.getLogger(PowerShell.class.getName()).log(Level.SEVERE, "Unexpected error when when closing PowerShell", ex);
            } finally {
                try {
                    p.getInputStream().close();
                    p.getErrorStream().close();
                } catch (IOException ex) {
                    Logger.getLogger(PowerShell.class.getName()).log(Level.SEVERE, "Unexpected error when when closing streams", ex);
                }                
                commandWriter.close();
                if (this.threadpool != null) {
                    try {
                        this.threadpool.shutdownNow();                        
                        this.threadpool.awaitTermination(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Logger.getLogger(PowerShell.class.getName()).log(Level.SEVERE, "Unexpected error when when shutting thread pool", ex);
                    }
                    
                }
                this.closed = true;
            }
        }
    }

    private void waitUntilClose(Future<String> task) throws InterruptedException {
        int closingTime = 0;
        while (!task.isDone()) {
            if (closingTime > maxWait) {
                Logger.getLogger(PowerShell.class.getName()).log(Level.SEVERE, "Unexpected error when closing PowerShell: TIMEOUT!");
                break;
            }
            Thread.sleep(this.waitPause);
            closingTime += this.waitPause;
        }
    }

    /**
     * Execute a single command in PowerShell console and gets result
     * 
     * @param command the command to execute
     * @return response with the output of the command
     */
    public static PowerShellResponse executeSingleCommand(String command) {
        PowerShell session = null;
        PowerShellResponse response = null;
        try {
            session = PowerShell.openSession();

            response = session.executeCommand(command);
        } catch (PowerShellNotAvailableException ex) {
            Logger.getLogger(PowerShell.class.getName()).log(Level.SEVERE, "PowerShell not available", ex);
        } finally {
            if (session != null) {
                session.close();
            }
        }
        return response;
    }
}
