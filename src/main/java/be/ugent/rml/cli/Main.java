package be.ugent.rml.cli;

import be.ugent.rml.DataFetcher;
import be.ugent.rml.Executor;
import be.ugent.rml.Utils;
import be.ugent.rml.functions.FunctionLoader;
import be.ugent.rml.records.RecordsFactory;
import be.ugent.rml.store.Quad;
import be.ugent.rml.store.QuadStore;
import be.ugent.rml.store.TriplesQuads;
import ch.qos.logback.classic.Level;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String [] args) {
        Options options = new Options();
        Option mappingdoc = Option.builder("m")
                .longOpt( "mapping" )
                .hasArg()
                .desc(  "path to mapping document" )
                .build();
        Option outputfile = Option.builder("o")
                .longOpt( "outputfile" )
                .hasArg()
                .desc(  "path to output file" )
                .build();
        Option functionfile = Option.builder("f")
                .longOpt("functionfile")
                .hasArg()
                .desc("path to functions.ttl file (dynamic functions are found relative to functions.ttl)")
                .build();
        Option triplesmaps = Option.builder("t")
                .longOpt( "triplesmaps" )
                .hasArg()
                .desc(  "IRIs of the triplesmaps that should be executed (default is all triplesmaps)" )
                .build();
        Option removeduplicates = Option.builder("d")
                .longOpt( "duplicates" )
                .desc(  "remove duplicates" )
                .build();
        Option configfile = Option.builder("c")
                .longOpt( "configfile" )
                .hasArg()
                .desc( "path to configuration file" )
                .build();
        Option help = Option.builder("h")
                .longOpt( "help" )
                .desc( "get help info" )
                .build();
        Option verbose = Option.builder("v")
                .longOpt( "verbose" )
                .desc( "verbose" )
                .build();
        options.addOption(mappingdoc);
        options.addOption(outputfile);
        options.addOption(functionfile);
        options.addOption(removeduplicates);
        options.addOption(triplesmaps);
        options.addOption(configfile);
        options.addOption(help);
        options.addOption(verbose);

        CommandLineParser parser = new DefaultParser();
        try {
            // parse the command line arguments
            CommandLine lineArgs = parser.parse(options, args);
            String[] configFileArgs = null;

            // Check if config file is given
            Properties configFile = null;
            if (lineArgs.hasOption("c")) {
                configFile = new Properties();
                configFile.load(Utils.getReaderFromLocation(lineArgs.getOptionValue("c")));
            }

            if (checkOptionPresence(help, lineArgs, configFile)) {
                printHelp(options);
                return;
            }

            if (checkOptionPresence(verbose, lineArgs, configFile)) {
                setLoggerLevel(Level.DEBUG);
            } else {
                setLoggerLevel(Level.ERROR);
            }

            String mOptionValue = getPriorityOptionValue(mappingdoc, lineArgs, configFile);
            if (mOptionValue == null) {
                printHelp(options);
            } else {
                File mappingFile = Utils.getFile(mOptionValue);
                QuadStore rmlStore = Utils.readTurtle(mappingFile);
                RecordsFactory factory = new RecordsFactory(new DataFetcher(System.getProperty("user.dir"), rmlStore));
                Executor executor;

                String fOptionValue = getPriorityOptionValue(functionfile, lineArgs, configFile);
                if (fOptionValue == null) {
                    executor = new Executor(rmlStore, factory);
                } else {
                    File functionFile = Utils.getFile(fOptionValue);
                    FunctionLoader functionLoader = new FunctionLoader(functionFile);
                    executor = new Executor(rmlStore, factory, functionLoader);
                }

                List<String> triplesMaps = new ArrayList<>();

                String tOptionValue = getPriorityOptionValue(triplesmaps, lineArgs, configFile);
                if (tOptionValue != null) {
                    triplesMaps = Arrays.asList(tOptionValue.split(","));
                }

                QuadStore result = executor.execute(triplesMaps, checkOptionPresence(removeduplicates, lineArgs, configFile));

                TriplesQuads tq = Utils.getTriplesAndQuads(result.getQuads(null, null, null, null));

                String outputFile = getPriorityOptionValue(outputfile, lineArgs, configFile);
                if (!tq.getTriples().isEmpty()) {
                    //write triples
                    writeOutput("triple", tq.getTriples(), "nt", outputFile);
                }

                if (!tq.getQuads().isEmpty()) {
                    //write quads
                    writeOutput("quad", tq.getQuads(), "nq", outputFile);
                }
            }
        } catch( ParseException exp ) {
            // oops, something went wrong
            logger.error("Parsing failed. Reason: " + exp.getMessage());
            printHelp(options);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }

    }

    private static boolean checkOptionPresence(Option option, CommandLine lineArgs, Properties properties) {
        return lineArgs.hasOption(option.getOpt()) || (properties != null
                && properties.getProperty(option.getLongOpt()) != null
                && !properties.getProperty(option.getLongOpt()).equals("false"));  // ex: 'help = false' in the config file shouldn't return the help text
    }
    private static String getPriorityOptionValue(Option option, CommandLine lineArgs, Properties properties) {
        if (lineArgs.hasOption(option.getOpt())) {
            return lineArgs.getOptionValue(option.getOpt());
        } else if (properties.getProperty(option.getLongOpt()) != null) {
            return properties.getProperty(option.getLongOpt());
        } else {
            return null;
        }
    }

    private static void printHelp(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("java -jar mapper.jar <options>\noptions:", options);
    }

    private static void setLoggerLevel(Level level) {
        Logger root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ((ch.qos.logback.classic.Logger) root).setLevel(level);
    }

    private static void writeOutput(String what, List<Quad> output, String extension, String outputFile) {
        if (output.size() > 1) {
            logger.info(output.size() + " " + what + "s were generated");
        } else {
            logger.info(output.size() + " " + what + " was generated");
        }

        //if output file provided, write to triples output file
        if (outputFile != null) {
            File targetFile = new File(outputFile + "." + extension);
            logger.info("Writing " + what + " to " + targetFile.getPath() + "...");

            if (!targetFile.isAbsolute()) {
                targetFile = new File(System.getProperty("user.dir") + "/" + outputFile + "." +  extension);
            }

            try {
                BufferedWriter out = new BufferedWriter(new FileWriter(outputFile));

                if (what.equals("triple")) {
                    Utils.toNTriples(output, out);
                } else {
                    Utils.toNQuads(output, out);
                }

                out.close();
                logger.info("Writing to " + targetFile.getPath() + " is done.");
            } catch(IOException e) {
                System.err.println( "Writing output to file failed. Reason: " + e.getMessage() );
            }
        } else {
            if (what.equals("triple")) {
                System.out.println(Utils.toNTriples(output));
            } else {
                System.out.println(Utils.toNQuads(output));
            }
        }
    }
}
