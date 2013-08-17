package judge.server;


import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Calendar;
import judge.client.Codes;

public class JudgeServer implements Codes {

    static String testDirectory = "C:/Judge/";
    static String javaCompile = "\"C:/Program Files (x86)/Java/jdk1.7.0_02/bin/javac\"";
    static String javaExec = "\"C:/Program Files (x86)/Java/jdk1.7.0_02/bin/java\"";

    final static long USACO_TIME_LIMIT = 2000;
    final static long OTHER_TIME_LIMIT = 30000;

    public static void main(String[] args) {
        if(args.length == 3) {
            testDirectory=args[0];
            javaCompile=args[1];
            javaExec=args[2];
        }
        else if(args.length == 0) {
            System.out.println("For custom parameters, specifiy testDirectory, javacPath, and javaPath in order.");
        }
        while (true) { //Ultimate failsafe
            InputStream read = null;
            OutputStream sendInfo = null;
            String dirName = null;
            Socket socket = null;
            ServerSocket servSocket = null;
            try {
                //Prepare to receive data:
                servSocket = new ServerSocket(13786);
                System.out.print("Awaiting connection... ");
                socket = servSocket.accept();

                //Connection to client established
                System.out.print("Now judging... ");

                //Create a testing directory
                dirName = testDirectory + System.nanoTime() + "/";
                File newDir = new File(dirName);
                newDir.mkdir();

                //Receive the file
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                read = socket.getInputStream();
                int len;
                byte[] buffer = new byte[4096];
                while ((len = read.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }

                //Finished receiving data
                socket.shutdownInput();

                //Split apart header
                String fileContents = baos.toString();
                String[] sects = fileContents.split("===END HEADER===");
                String header = sects[0];
                String[] headerVals = header.split("===SPLIT HEADER===");
                String name = headerVals[0];

                //Write file to testing directory
                FileOutputStream fos = new FileOutputStream(dirName + name);
                fos.write(fileContents.substring(fileContents.indexOf("===END HEADER===") + 16).getBytes());
                fos.close();

                //Prepare to send data
                sendInfo = socket.getOutputStream();
                sendInfo.write(JUDGING_INIT);

                //Compile received program
                String language = headerVals[4];
                switch (language) {
                    case "Java": //Received java program
                        //Compile program
                        ProcessBuilder builder = new ProcessBuilder(new String[]{javaCompile, dirName + name});
                        Process comp = builder.start();
                        int compileResult = comp.waitFor();
                        if (compileResult != 0) { //Compile failed
                            sendInfo.write(COMPILE_FAIL);
                            sendInfo.write(JUDGING_ABORT);
                            System.out.println("Compile fail.");
                            break;
                        }
                        sendInfo.write(COMPILE_PASS); //Compile succeeded
                        boolean success = true;
                        int tests = testCount(headerVals); //Check how many tests need to be run
                        if (tests == 0) { //Record of problem was not found
                            sendInfo.write(INVALID_PROBLEM);
                            sendInfo.write(JUDGING_ABORT);
                            System.out.println("Invalid problem.");
                            break;
                        }

                        //Run each test case
                        outerLoop:
                        for (int i = 1; i <= tests; i++) {
                            //Move test files into place
                            prepare(headerVals, dirName, i);
                            //Execute program
                            builder = new ProcessBuilder(new String[]{javaExec, name.split("\\.")[0]});
                            new File(dirName + "input.in").createNewFile();
                            new File(dirName + "output.out").createNewFile();
                            builder.redirectInput(new File(dirName + "input.in")); //Redirect standard input to test case input
                            builder.redirectOutput(new File(dirName + "output.out")); //Redirect standard output to test case output
                            builder.directory(new File(dirName)); //Execute from testing directory
                            Process run = builder.start();
                            //Monitor time limit
                            long startTime = Calendar.getInstance().getTimeInMillis();
                            while (true) {
                                try {
                                    run.exitValue(); //Throws if not completed
                                } catch (Exception e) {
                                    long timeDif = Calendar.getInstance().getTimeInMillis() - startTime;
                                    if (!timeOkay(headerVals, timeDif)) { //Time limit exceeded
                                        sendInfo.write(TEST_FAIL_TIMEOUT);
                                        success = false;
                                        run.destroy();
                                        System.out.println("T.");
                                        break outerLoop;
                                    }
                                    continue;
                                }
                                break;
                            }
                            //Program completed
                            boolean correct = checkResult(headerVals, dirName, i); //Check test case
                            if (correct) { //Test passed
                                sendInfo.write(TEST_PASS);
                                System.out.print(i + " ");
                            } else { //Test failed
                                sendInfo.write(TEST_FAIL_WRONG);
                                System.out.print("X");
                                success = false;
                                break;
                            }
                        }
                        if (success) { //All tests passed
                            sendInfo.write(TESTS_GOOD);
                            System.out.println("... Pass.");
                        } else { //Test failed
                            sendInfo.write(TESTS_BAD);
                            System.out.println("... Fail.");
                        }
                        break;
                    case "Python": //Todo: python
                        break;
                    case "C++": //Todo: C++
                        break;
                }
            } catch (Exception e) {
                try { //Attempt to send failure notification
                    sendInfo.write(JUDGING_ERROR);
                    sendInfo.write(JUDGING_ABORT);
                } catch (Exception ex) {
                }
                e.printStackTrace();
            } finally { //Close streams
                try {
                    socket.close();
                    servSocket.close();
                    read.close();
                    sendInfo.close();
                    //Delete testing directory
                    File dir = new File(dirName);
                    for (File subfile : dir.listFiles()) {
                        subfile.delete();
                    }
                    dir.delete();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static boolean timeOkay(String[] headers, long timeInMillis) {
        switch (headers[1]) {
            case "USACO":
                return timeInMillis <= USACO_TIME_LIMIT;
            case "WPI":
                return timeInMillis <= OTHER_TIME_LIMIT;
            case "FSU":
                return timeInMillis <= OTHER_TIME_LIMIT;
            case "PClassic":
                return timeInMillis <= OTHER_TIME_LIMIT;
        }
        return true;
    }

    public static int testCount(String[] headers) throws IOException {
        for (int i = 0; i < Integer.MAX_VALUE; i++) { //Count number of files of form #.in
            File databank = new File(testDirectory + "Testing Data/" + headers[1] + "." + headers[2] + "." + headers[3] + "/" + (i + 1) + ".in");
            if (!databank.exists()) {
                return i;
            }
        }
        return 0;
    }

    public static String getInput(String[] headers, int testNumber) throws IOException {
        File databank = new File(testDirectory + "Testing Data/" + headers[1] + "." + headers[2] + "." + headers[3] + "/" + testNumber + ".in"); //Reads input for test case
        return readAll(databank);
    }

    public static String getUSACOName(String[] headers) throws IOException {
        File databank = new File(testDirectory + "Testing Data/" + headers[1] + "." + headers[2] + "." + headers[3] + "/name.txt"); //Retrieves a USACO in/out name
        return readAll(databank);
    }

    public static void prepare(String[] headers, String dirName, int testNumber) throws IOException {
        String origin = headers[1];
        switch (origin) {
            case "USACO": //USACO problem; requires custom IO
                //Create output file
                File tbout = new File(dirName + getUSACOName(headers) + ".out");
                if (tbout.exists()) {
                    tbout.delete();
                }
                //Write input file
                FileOutputStream fos = new FileOutputStream(dirName + getUSACOName(headers) + ".in");
                fos.write(getInput(headers, testNumber).getBytes());
                fos.close();
                break;
            default:
                //Create output file
                File tbout2 = new File(dirName + "output.out");
                if (tbout2.exists()) {
                    tbout2.delete();
                }
                //Write input file
                FileOutputStream fos2 = new FileOutputStream(dirName + "input.in");
                fos2.write(getInput(headers, testNumber).getBytes());
                fos2.close();
                break;
        }
    }

    public static String readAll(File file) throws IOException { //Reads in a file
        FileInputStream fis = new FileInputStream(file);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int len;
        byte[] buffer = new byte[4096];
        while ((len = fis.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        fis.close();
        return baos.toString();
    }

    public static String expectedResult(String[] headers, String dirName, int testNumber) throws IOException {
        //Load the correct result for a test
        File databank = new File(testDirectory + "Testing Data/" + headers[1] + "." + headers[2] + "." + headers[3] + "/" + testNumber + ".out");
        return readAll(databank);
    }

    public static boolean checkResult(String[] headers, String dirName, int testNumber) throws IOException {
        //Compare the actual result to the expected result
        String origin = headers[1];
        switch (origin) {
            case "USACO":
                File result = new File(dirName + getUSACOName(headers) + ".out");
                if (!result.exists()) {
                    return false;
                }
                String st = readAll(result).replaceAll("\r", "");
                String expected = expectedResult(headers, dirName, testNumber).replaceAll("\r", "");
                if (st.contentEquals(expected)) {
                    return true;
                }
                return false;
            default:
                File result2 = new File(dirName + "output.out");
                if (!result2.exists()) {
                    return false;
                }
                String st2 = readAll(result2).replaceAll("\r", "");
                String expected2 = expectedResult(headers, dirName, testNumber).replaceAll("\r", "");
                if (st2.contentEquals(expected2)) {
                    return true;
                }
                return false;
        }
    }
}
