package com.hazelcast.simulator.agent.workerjvm;

import com.hazelcast.simulator.agent.Agent;
import com.hazelcast.simulator.agent.SpawnWorkerFailedException;
import com.hazelcast.simulator.protocol.configuration.Ports;
import com.hazelcast.simulator.worker.WorkerType;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hazelcast.simulator.utils.CommonUtils.sleepSeconds;
import static com.hazelcast.simulator.utils.FileUtils.deleteQuiet;
import static com.hazelcast.simulator.utils.FileUtils.ensureExistingDirectory;
import static com.hazelcast.simulator.utils.FileUtils.getSimulatorHome;
import static com.hazelcast.simulator.utils.FileUtils.readObject;
import static com.hazelcast.simulator.utils.FileUtils.writeText;
import static com.hazelcast.simulator.utils.NativeUtils.execute;
import static java.lang.String.format;
import static java.util.Arrays.asList;

public class WorkerJvmLauncher {

    private static final Logger LOGGER = Logger.getLogger(WorkerJvmLauncher.class);

    private static final String CLASSPATH = System.getProperty("java.class.path");
    private static final File SIMULATOR_HOME = getSimulatorHome();
    private static final String CLASSPATH_SEPARATOR = System.getProperty("path.separator");
    private static final AtomicInteger WORKER_INDEX_GENERATOR = new AtomicInteger();
    private static final String WORKERS_PATH = getSimulatorHome().getAbsolutePath() + "/workers";

    private final List<WorkerJvm> workersInProgress = new LinkedList<WorkerJvm>();
    private final AtomicBoolean javaHomePrinted = new AtomicBoolean();

    private final Agent agent;
    private final ConcurrentMap<String, WorkerJvm> workerJVMs;
    private final WorkerJvmSettings settings;

    private File memberHzConfigFile;
    private File clientHzConfigFile;
    private File log4jFile;
    private File testSuiteDir;

    public WorkerJvmLauncher(Agent agent, ConcurrentMap<String, WorkerJvm> workerJVMs, WorkerJvmSettings settings) {
        this.agent = agent;
        this.workerJVMs = workerJVMs;
        this.settings = settings;
    }

    public void launch() throws Exception {
        memberHzConfigFile = createTmpXmlFile("hazelcast", settings.memberHzConfig);
        clientHzConfigFile = createTmpXmlFile("client-hazelcast", settings.clientHzConfig);
        log4jFile = createTmpXmlFile("worker-log4j", settings.log4jConfig);

        testSuiteDir = agent.getTestSuiteDir();
        if (!testSuiteDir.exists()) {
            if (!testSuiteDir.mkdirs()) {
                throw new SpawnWorkerFailedException("Couldn't create testSuiteDir: " + testSuiteDir.getAbsolutePath());
            }
        }

        LOGGER.info("Spawning Worker JVM using settings: " + settings);
        spawn(settings.memberWorkerCount, WorkerType.MEMBER);
        spawn(settings.clientWorkerCount, WorkerType.CLIENT);
    }

    private File createTmpXmlFile(String name, String content) throws IOException {
        File tmpXmlFile = File.createTempFile(name, ".xml");
        tmpXmlFile.deleteOnExit();
        writeText(content, tmpXmlFile);

        return tmpXmlFile;
    }

    private void spawn(int count, WorkerType type) throws Exception {
        LOGGER.info(format("Starting %s %s worker Java Virtual Machines", count, type));

        for (int i = 0; i < count; i++) {
            WorkerJvm worker = startWorkerJvm(type);
            workersInProgress.add(worker);
        }

        LOGGER.info(format("Finished starting %s %s worker Java Virtual Machines", count, type));

        waitForWorkersStartup(workersInProgress, settings.workerStartupTimeout);
        workersInProgress.clear();
    }

    private WorkerJvm startWorkerJvm(WorkerType type) throws IOException {
        int workerIndex = WORKER_INDEX_GENERATOR.incrementAndGet();
        int workerPort = Ports.WORKER_START_PORT + workerIndex;
        String workerId = "worker-" + agent.getPublicAddress() + "-" + workerIndex + "-" + type;
        File workerHome = new File(testSuiteDir, workerId);
        ensureExistingDirectory(workerHome);

        String javaHome = getJavaHome(settings.javaVendor, settings.javaVersion);

        WorkerJvm workerJvm = new WorkerJvm(workerId, workerIndex, workerPort, workerHome, type);

        generateWorkerStartScript(type, workerJvm, workerIndex, workerPort);

        ProcessBuilder processBuilder = new ProcessBuilder(new String[]{"bash", "worker.sh"})
                .directory(workerHome)
                .redirectErrorStream(true);

        Map<String, String> environment = processBuilder.environment();
        String path = javaHome + File.pathSeparator + "bin:" + environment.get("PATH");
        environment.put("PATH", path);
        environment.put("JAVA_HOME", javaHome);

        Process process = processBuilder.start();
        File logFile = new File(workerHome, "out.log");
        new WorkerJvmProcessOutputGobbler(process.getInputStream(), new FileOutputStream(logFile)).start();
        workerJvm.setProcess(process);
        copyResourcesToWorkerId(workerId);
        workerJVMs.put(workerId, workerJvm);

        return workerJvm;
    }

    private void waitForWorkersStartup(List<WorkerJvm> workers, int workerTimeoutSec) {
        List<WorkerJvm> todo = new ArrayList<WorkerJvm>(workers);
        for (int i = 0; i < workerTimeoutSec; i++) {
            Iterator<WorkerJvm> iterator = todo.iterator();
            while (iterator.hasNext()) {
                WorkerJvm jvm = iterator.next();

                if (hasExited(jvm)) {
                    throw new SpawnWorkerFailedException(format("Startup failure: worker on host %s failed during startup,"
                            + " check '%s/out.log' for more information!", agent.getPublicAddress(), jvm.getWorkerHome()));
                }

                String address = readAddress(jvm);
                if (address != null) {
                    jvm.setMemberAddress(address);

                    iterator.remove();
                    LOGGER.info(format("Worker: %s Started %s of %s", jvm.getId(), workers.size() - todo.size(), workers.size()));
                }
            }

            if (todo.isEmpty()) {
                return;
            }

            sleepSeconds(1);
        }

        workerTimeout(workerTimeoutSec, todo);
    }

    @SuppressWarnings("unused")
    private String getJavaHome(String javaVendor, String javaVersion) {
        String javaHome = System.getProperty("java.home");
        if (javaHomePrinted.compareAndSet(false, true)) {
            LOGGER.info("java.home=" + javaHome);
        }
        return javaHome;
    }

    private void generateWorkerStartScript(WorkerType type, WorkerJvm workerJvm, int workerIndex, int workerPort) {
        String[] args = buildArgs(workerJvm, type, workerIndex, workerPort);
        File startScript = new File(workerJvm.getWorkerHome(), "worker.sh");

        StringBuilder sb = new StringBuilder("#!/bin/bash\n");
        for (String arg : args) {
            sb.append(arg).append(" ");
        }
        //sb.append(" > sysout.log");
        sb.append("\n");

        writeText(sb.toString(), startScript);
    }

    private void copyResourcesToWorkerId(String workerId) {
        final String testSuiteId = agent.getTestSuite().id;
        File uploadDirectory = new File(WORKERS_PATH + "/" + testSuiteId + "/upload/");
        if (!uploadDirectory.exists()) {
            LOGGER.debug("Skip copying upload directory to workers since no upload directory was found");
            return;
        }
        String cpCommand = format("cp -rfv %s/%s/upload/* %s/%s/%s/",
                WORKERS_PATH,
                testSuiteId,
                WORKERS_PATH,
                testSuiteId,
                workerId);
        execute(cpCommand);
        LOGGER.info(format("Finished copying '+%s+' to worker", WORKERS_PATH));
    }

    private boolean hasExited(WorkerJvm workerJvm) {
        try {
            workerJvm.getProcess().exitValue();
            return true;
        } catch (IllegalThreadStateException e) {
            return false;
        }
    }

    private String readAddress(WorkerJvm jvm) {
        File file = new File(jvm.getWorkerHome(), "worker.address");
        if (!file.exists()) {
            return null;
        }

        String address = readObject(file);
        deleteQuiet(file);

        return address;
    }

    private void workerTimeout(int workerTimeoutSec, List<WorkerJvm> todo) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        sb.append(todo.get(0).getId());
        for (int i = 1; i < todo.size(); i++) {
            sb.append(",").append(todo.get(i).getId());
        }
        sb.append("]");

        throw new SpawnWorkerFailedException(format(
                "Timeout: workers %s of testsuite %s on host %s didn't start within %s seconds",
                sb, agent.getTestSuite().id, agent.getPublicAddress(),
                workerTimeoutSec));
    }

    private String[] buildArgs(WorkerJvm workerJvm, WorkerType type, int workerIndex, int workerPort) {
        List<String> args = new LinkedList<String>();

        addNumaCtlSettings(args);
        addProfilerSettings(workerJvm, args);

        args.add("-classpath");
        args.add(getClasspath());
        args.addAll(getJvmOptions(settings, type));
        args.add("-XX:OnOutOfMemoryError=\"touch worker.oome\"");
        args.add("-Dhazelcast.logging.type=log4j");
        args.add("-Dlog4j.configuration=file:" + log4jFile.getAbsolutePath());

        args.add("-DSIMULATOR_HOME=" + SIMULATOR_HOME);
        args.add("-DworkerId=" + workerJvm.getId());
        args.add("-DworkerType=" + type);
        args.add("-DpublicAddress=" + agent.getPublicAddress());
        args.add("-DagentIndex=" + agent.getAddressIndex());
        args.add("-DworkerIndex=" + workerIndex);
        args.add("-DworkerPort=" + workerPort);
        args.add("-DautoCreateHZInstances=" + settings.autoCreateHZInstances);
        args.add("-DmemberHzConfigFile=" + memberHzConfigFile.getAbsolutePath());
        args.add("-DclientHzConfigFile=" + clientHzConfigFile.getAbsolutePath());

        // add class name to start correct worker type
        args.add(type.getClassName());

        return args.toArray(new String[args.size()]);
    }

    private void addNumaCtlSettings(List<String> args) {
        String numaCtl = settings.numaCtl;
        if (!"none".equals(numaCtl)) {
            args.add(numaCtl);
        }
    }

    private void addProfilerSettings(WorkerJvm workerJvm, List<String> args) {
        String profiler = settings.profiler;
        if ("perf".equals(profiler)) {
            // perf command always need to be in front of the java command.
            args.add(settings.perfSettings);
            args.add("java");
        } else if ("vtune".equals(profiler)) {
            // vtune command always need to be in front of the java command.
            args.add(settings.vtuneSettings);
            args.add("java");
        } else if ("yourkit".equals(profiler)) {
            args.add("java");
            String agentSetting = settings.yourkitConfig
                    .replace("${SIMULATOR_HOME}", SIMULATOR_HOME.getAbsolutePath())
                    .replace("${WORKER_HOME}", workerJvm.getWorkerHome().getAbsolutePath());
            args.add(agentSetting);
        } else if ("hprof".equals(profiler)) {
            args.add("java");
            args.add(settings.hprofSettings);
        } else if ("flightrecorder".equals(profiler)) {
            args.add("java");
            args.add(settings.flightrecorderSettings);
        } else {
            args.add("java");
        }
    }

    private String getClasspath() {
        File libDir = new File(agent.getTestSuiteDir(), "lib");
        return CLASSPATH + CLASSPATH_SEPARATOR
                + SIMULATOR_HOME + "/user-lib/*" + CLASSPATH_SEPARATOR
                + new File(libDir, "*").getAbsolutePath();
    }

    private List<String> getJvmOptions(WorkerJvmSettings settings, WorkerType type) {
        String workerVmOptions;
        if (type == WorkerType.MEMBER) {
            workerVmOptions = settings.vmOptions;
        } else {
            workerVmOptions = settings.clientVmOptions;
        }

        String[] vmOptionsArray = new String[]{};
        if (workerVmOptions != null && !workerVmOptions.trim().isEmpty()) {
            vmOptionsArray = workerVmOptions.split("\\s+");
        }
        return asList(vmOptionsArray);
    }
}
