package dev.g8lol.parser;

import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvException;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * @author G8LOL
 * @since 9/30/2022
 */
public class Main {

    public static final String NAME = "SEARCH";

    private static String customString;
    private static ExecutorService executorService;

    private static final HashSet<String> DATABASE_SET = new HashSet<>();
    private static final HashMap<String, FileType> DATABASE_SOURCE_SET = new HashMap<>();

    private static Map<String, Object> configMap = new HashMap<>();

    private static final List<String> OTHER_DELIMITERS = Arrays.asList("-", ";", ":", ",", "|"),
            WRAPPINGS = Arrays.asList("'", "\"");

    private static final List<Character> CSV_DELIMITERS = Arrays.asList(',', ';', '|', '\t', ' ');

    public static void main(String[] args) throws IOException {
        System.out.println(PaletteUtil.ANSI_CYAN + "    --------------------- Start ---------------------\n" + PaletteUtil.ANSI_RESET);

        final Scanner scanner = new Scanner(System.in);
        final StringBuilder configBuilder = new StringBuilder();

        PrintUtil.print("Attempting to load config file");

        final File configFile = new File("config.json");

        if (!configFile.exists()) {
            PrintUtil.print("No config file found, creating..");

            if (configFile.createNewFile()) {
                //write to config file
                Files.write(configFile.toPath(),
                        """
                        {
                            "ignorecase": false,
                            "contains": false,
                            "crossreference": false,
                            "unwrap": false,
                            "bases": []
                        }
                        """.getBytes());

                PrintUtil.print("Config file created");
            } else {
                PrintUtil.print("Failed to create config file! - Exiting");
                return;
            }
        }

        //read config
        Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8).forEach(line -> configBuilder.append(line).append("\n"));

        PrintUtil.print("Config file loaded");

        //create jsonobject from config
        final JSONObject configObject = new JSONObject(configBuilder.toString());

        //get config from json object
        configMap = configObject.toMap();

        if (configMap.containsKey("bases")) {
            final JSONArray jsonArray = configObject.getJSONArray("bases");

            //get the paths/urls of the dbs and add them
            final Set<String> sources = IntStream
                    .range(0, jsonArray.length())
                    .mapToObj(jsonArray::getString).collect(Collectors.toSet());

            DATABASE_SET.addAll(sources);
        }

        //create the thread pool
        executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        //load dbs
        final long time = System.currentTimeMillis();
        final List<Integer> lengths = new ArrayList<>();

        if (!DATABASE_SET.isEmpty()) {
            DATABASE_SET.forEach(additionalBase -> {
                try {
                    //submit the task to the thread pool
                    final Tuple<String, Integer, FileType> tuple = submitToPool(additionalBase);

                    //add the source and filetype to the map
                    DATABASE_SOURCE_SET.put(tuple.t(), tuple.u());
                    lengths.add(tuple.s());
                } catch (final ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            });
        } else {
            PrintUtil.print("No databases entered - Exiting");
            return;
        }

        //stuff
        PrintUtil.print("Loaded database(s) - time taken (ms): " + (System.currentTimeMillis() - time) + "\n");

        System.out.println(PaletteUtil.ANSI_YELLOW + "Lines:" + PaletteUtil.ANSI_RESET);
        lengths.forEach(length -> System.out.println(PaletteUtil.ANSI_RED + "[" + length + "]" + PaletteUtil.ANSI_RESET));
        System.out.println();

        while (true) {
            //settings
            Method method = null;
            final StringBuilder finalInfo = new StringBuilder();
            final String term;
            final int methodNum;

            PrintUtil.printQuestion("Enter Search Type or Stop (1:Username 2:Email 3:Uid 4:IP 5:Custom 6:Stop): ");

            methodNum = scanner.nextInt();

            //get the method (doesnt matter if its txt)
            switch (methodNum) {
                case 1 -> method = Method.USERNAME;
                case 2 -> method = Method.EMAIL;
                case 3 -> method = Method.UID;
                case 4 -> method = Method.IP;
                case 5 -> {
                    PrintUtil.printQuestion("Enter Custom Search Type: ");

                    customString = scanner.next();
                    method = Method.CUSTOM;
                }
                case 6 -> System.exit(6969);
                default -> {
                    PrintUtil.print("Wrong search type! (Has to be 1 - 5)");
                    continue;
                }
            }

            //get the term to search for
            PrintUtil.printQuestion("Enter Search Term: ");

            term = scanner.next();

            //actual start
            PrintUtil.print("Checking Databases..");

            final Method finalMethod = method;

            final long initialTime = System.currentTimeMillis();

            //iterate through each database
            DATABASE_SOURCE_SET.forEach((source, fileType) -> {
                //get the set of found information
                final HashSet<JSONObject> jsonObjectList = searchDatabase(source, finalMethod, term, fileType);
                final HashSet<Object> referenceValues = new HashSet<>();

                if (jsonObjectList != null && !jsonObjectList.isEmpty()) {
                    PrintUtil.print("Found Information In Databases");

                    finalInfo.append("\n").append(PaletteUtil.ANSI_CYAN).append(String.format("-- Information On Searched Term - %s: --", term)).append(PaletteUtil.ANSI_RESET).append("\n\n");

                    //iterate through all the found information
                    jsonObjectList.forEach(jsonObject2 ->
                            //get the keys and values of the jsonobject through a map
                            jsonObject2.toMap().forEach((key, value) -> {
                                finalInfo.append(key).append(": ").append(value.toString()).append("\n");

                                //add reference value
                                if ((boolean) configMap.get("crossreference"))
                                    referenceValues.add(value);
                            })
                    );

                    //start the cross-reference
                    if ((boolean) configMap.get("crossreference")) {
                        final HashMap<String, FileType> tempSources = new HashMap<>(DATABASE_SOURCE_SET);
                        //hashmap remove should take O(1)
                        tempSources.remove(source);

                        finalInfo.append("\n").append("[-- Cross Reference: --]").append("\n").append("\n");

                        //iterate through all the sources
                        tempSources.forEach((src, fileType1) -> {
                            //get the reference values
                            final HashSet<JSONObject> referenceInfo = crossReferenceInformation(src, referenceValues, fileType1);

                            //iterate through all the found information and add it to the final info
                            if (referenceInfo != null && !referenceInfo.isEmpty()) {
                                referenceInfo.forEach(ref ->
                                        ref.toMap().forEach((key, value) ->
                                                finalInfo.append(key).append(": ").append(Optional.ofNullable(value).orElse("None")).append("\n"))
                                );
                            }
                        });
                    }

                    PrintUtil.print("Added Information");
                }
            });

            //print the final info
            if (!finalInfo.isEmpty()) {
                PrintUtil.print("Printing Information - Time Taken: " + (System.currentTimeMillis() - initialTime) + "ms");

                System.out.println(finalInfo);
            } else
                PrintUtil.print("Nothing found at all :(");


            System.out.println(PaletteUtil.ANSI_CYAN + "\n    --------------------- End ---------------------\n" + PaletteUtil.ANSI_RESET);
        }
    }

    /**
     * start loading a database by submitting it to the thread pool
     * @param url the url of the database to submit
     * @return a tuple containing the source and the length of the source
     * @throws ExecutionException if tasks are not completed correctly
     * @throws InterruptedException if the thread is interrupted
     */
    public static Tuple<String, Integer, FileType> submitToPool(final String url) throws ExecutionException, InterruptedException {
        final Future<Tuple<String, Integer, FileType>> future = executorService.submit(new DatabaseCallable(url));

        return future.get();
    }

    /**
     * validate ip address
     * @param ip ip address to validate
     * @return true if ip, false if not
     */
    public static boolean isIP(final String ip) {
        final String[] parts = ip.split("\\.");

        if (parts.length != 4)
            return false;

        for (String s : parts) {
            try {
                final int i = Integer.parseInt(s);

                if ((i < 0) || (i > 255))
                    return false;
            } catch (final NumberFormatException nfe) {
                return false;
            }
        }

        return true;
    }

    /**
     * check if the string is a valid email
     * some strings may false
     * @param email email to check
     * @return true if email, false if not
     */
    public static boolean isEmail(final String email) {
        return email.matches("^[A-Za-z0-9+_.-]+@(.+)$");
    }

    /**
     * check if source is csv file
     * @param source source of database
     * @param url path/url of original file
     * @return true if csv, false if not
     */
    public static boolean isCSV(final String source, final String url) {
        try (final CSVReader ignored1 = new CSVReader(new StringReader(source))) {
            if (url.endsWith(".csv"))
                return true;
        } catch (final IOException ignored) {
            return false;
        }

        return false;
    }

    /**
     * check if source is json file
     * @param source source of database
     * @return true if json, false if not
     */
    public static boolean isJSON(final String source) {
        try {
            new JSONObject(source);
            return true;
        } catch (final JSONException ignored) {
            try {
                new JSONArray(source);
                return true;
            } catch (final JSONException ignored2) {
                return false;
            }
        }
    }

    /**
     * cross-reference a term with the other additional databases for further information (if any)
     *
     * @param source the database source
     * @param terms the terms to search for
     * @param fileType the file type of the database (json, csv, txt)
     * @return a set of json objects containing the information
     */
    public static HashSet<JSONObject> crossReferenceInformation(final String source, final HashSet<Object> terms, final FileType fileType) {
        switch (fileType) {
            case JSON -> {
                try {
                    final JSONArray jsonArray = new JSONArray(source);

                    //get all json objects in the json array
                    final HashSet<JSONObject> objects = (HashSet<JSONObject>) IntStream
                            .range(0, jsonArray.length())
                            .mapToObj(jsonArray::getJSONObject).collect(Collectors.toSet());

                    //predicate to check if the json object contains the term
                    final Predicate<JSONObject> jsonObjectPredicate =
                            jsonObject -> jsonObject.keySet().stream().anyMatch(key -> terms.contains(jsonObject.get(key)));

                    //filter the json objects to only contain the ones that contain the term
                    return objects
                            .stream()
                            .filter(jsonObjectPredicate)
                            .collect(Collectors.toCollection(HashSet::new));
                } catch (final JSONException ignored) {
                    //enables support for nested json arrays
                    final JSONObject jsonObject = new JSONObject(source);

                    //uses recursion to search through all the nested json objects
                    return jsonObject.keySet()
                            .stream()
                            .flatMap(key -> crossReferenceInformation(jsonObject.get(key).toString(), terms, fileType).stream())
                            .collect(Collectors.toCollection(HashSet::new));
                }
            }
            case CSV -> {
                List<String[]> lines = null;

                //get the delimiter
                for (char csvDelimiter : CSV_DELIMITERS) {
                    final CSVParser csvParser = new CSVParserBuilder()
                            .withSeparator(csvDelimiter)
                            .build();

                    final CSVReader csvReader = new CSVReaderBuilder(new StringReader(source))
                            .withCSVParser(csvParser)
                            .build();

                    //try to parse the csv and see if the delimiter is correct
                    try {
                        final List<String[]> allLines = csvReader.readAll();

                        final String[] keys = allLines.get(0);

                        if (keys.length > 1) {
                            lines = allLines;
                            break;
                        }
                    } catch (final IndexOutOfBoundsException | IOException | CsvException ignored) {}
                }

                //check if lines is null
                if (lines == null) return null;

                final String[] keys = lines.get(0);

                // key and value
                final HashSet<HashSet<Pair<String, String>>> testSet = new HashSet<>();

                /* gets each of the lines (string array) then maps each string
                in that array to a set of pairs with the key and value and adds it to the set */
                IntStream.rangeClosed(1, lines.size() - 1)
                        .mapToObj(lines::get)
                        .map(line -> IntStream.range(0, line.length)
                                .mapToObj(i -> new Pair<>(keys[i], line[i]))
                                .collect(Collectors.toCollection(HashSet::new)))
                        .forEach(testSet::add);

                //predicate to check if the set of pairs contains the term
                final Predicate<HashSet<Pair<String, String>>> setPredicate = set -> set.stream()
                        .anyMatch(pair -> terms.contains(pair.s()));

                final HashSet<JSONObject> objects = new HashSet<>();

                //filter the set of sets to only contain the ones that contain the term
                testSet.stream().filter(setPredicate).forEach(set -> set.forEach(pair -> {
                    if (pair.t().isBlank() || pair.s().isBlank()) {
                        //check if the set contains a duplicate key
                        if (objects.stream().anyMatch(jsonObject -> jsonObject.has(pair.t()))
                                || (pair.t().isBlank() && pair.s().isBlank()))
                            return;
                    }

                    final JSONObject jsonObject = new JSONObject();

                    jsonObject.put(pair.t(), pair.s());

                    objects.add(jsonObject);
                }));

                return objects;

            }
            case TXT -> {
                //split the source by new line and trim it (removes unnecessary leading and trailing whitespace)
                final HashSet<String> lines = Arrays.stream(source.split("\n"))
                        .map(String::trim)
                        .collect(Collectors.toCollection(HashSet::new));

                final HashSet<JSONObject> objects = new HashSet<>();

                //TODO guess delimiter when not in delimiter array

                for (String line : lines) {
                    final String finalLine = line;

                    //predicate to check if the elements in the terms set are lists or not and check if the line contains the term in the appropriate manner
                    final Predicate<Object> predicate = s -> {
                        if (s instanceof final ArrayList<?> arraylist)
                            return arraylist.stream().anyMatch(o ->
                                    (boolean) configMap.get("contains") ? finalLine.toLowerCase().contains(o.toString().toLowerCase()) : finalLine.equalsIgnoreCase(o.toString()));
                        else
                            return (boolean) configMap.get("contains") ? finalLine.toLowerCase().contains(s.toString().toLowerCase()) : finalLine.equalsIgnoreCase(s.toString());
                    };

                    //check if the line contains any of the terms
                    if (terms.stream().anyMatch(predicate)) {
                        //unwrap quotations when necessary
                        if ((boolean) configMap.get("unwrap")) {
                            for (String wrapping : WRAPPINGS) {
                                if (line.contains(wrapping)) {
                                    final boolean canUnwrap = StringUtils.countMatches(line, wrapping) % 2 == 0;

                                    if (canUnwrap) line = line.replace(wrapping, "");
                                }
                            }
                        }

                        //split the line by the delimiter (a regular expression/regex which matches one or more whitespaces)
                        List<String> splitLine = Arrays.asList(line.split("\\s+"));

                        //if the line is not able to be split try other delimiters
                        if (splitLine.size() <= 1) {
                            final List<String> triedDelimiters = tryDelimiters(line);

                            //update the original list
                            if (!triedDelimiters.isEmpty())
                                splitLine = triedDelimiters;
                        }

                        final JSONObject jsonObject = new JSONObject();

                        final JSONArray ipArray = new JSONArray(),
                                emailArray = new JSONArray(),
                                otherValueArray = new JSONArray(),
                                possibleDelimiterArray = new JSONArray();

                        //iterate through the split line and add the values to the appropriate array
                        splitLine.forEach(s -> {
                            if (isIP(s))
                                ipArray.put(s);
                            else if (isEmail(s))
                                emailArray.put(s);
                            else if (OTHER_DELIMITERS.contains(s.trim()))
                                possibleDelimiterArray.put(s);
                            else
                                otherValueArray.put(s);
                        });

                        if (!ipArray.isEmpty()) jsonObject.put("IP", ipArray);
                        if (!emailArray.isEmpty()) jsonObject.put("Email", emailArray);
                        if (!otherValueArray.isEmpty()) jsonObject.put("Other Values", otherValueArray);
                        if (!possibleDelimiterArray.isEmpty()) jsonObject.put("Possible Delimiters", possibleDelimiterArray);

                        objects.add(jsonObject);
                    }
                }

                return objects;
            }
        }

        return null;
    }

    /**
     * search database using source
     * @param source the source to search
     * @param method the method to search
     * @param term the term to search for
     * @param fileType the file type of the source
     * @return a set of json objects containing the information
     */
    public static HashSet<JSONObject> searchDatabase(final String source, final Method method, final String term, final FileType fileType) {
        switch (fileType) {
            case JSON -> {
                try {
                    final JSONArray jsonArray = new JSONArray(source);

                    //get all json objects in the json array
                    final HashSet<JSONObject> objects = (HashSet<JSONObject>) IntStream
                            .range(0, jsonArray.length())
                            .mapToObj(jsonArray::getJSONObject).collect(Collectors.toSet());

                    //predicate to filter the json objects by the term and method
                    final Predicate<JSONObject> jsonObjectPredicate =
                            jsonObject -> jsonObject.keySet()
                                    .stream()
                                    .anyMatch(key -> {
                                        if (key.equalsIgnoreCase(method == Method.CUSTOM ? customString : method.getMethodName())) {
                                            final String value = (boolean) configMap.get("ignorecase") ? jsonObject.getString(key).toLowerCase() : jsonObject.getString(key);
                                            final String sensitiveTerm = (boolean) configMap.get("ignorecase") ? term.toLowerCase() : term;

                                            return (boolean) configMap.get("contains") ? value.contains(sensitiveTerm) : sensitiveTerm.equals(value);
                                        }

                                        return false;
                                    });

                    //filter the json objects by the predicate and return a hashset of the filtered objects
                    return objects
                            .stream()
                            .filter(jsonObjectPredicate)
                            .collect(Collectors.toCollection(HashSet::new));
                } catch (final JSONException ignored) {
                    //enables support for nested json arrays
                    final JSONObject jsonObject = new JSONObject(source);

                    //uses recursion to search through nested json objects and get the json array (if any)
                    return jsonObject.keySet()
                            .stream()
                            .flatMap(key -> searchDatabase(jsonObject.get(key).toString(), method, term, fileType).stream())
                            .collect(Collectors.toCollection(HashSet::new));
                }
            }
            case CSV -> {
                List<String[]> lines = null;

                //get the delimiter
                for (char csvDelimiter : CSV_DELIMITERS) {
                    final CSVParser csvParser = new CSVParserBuilder()
                            .withSeparator(csvDelimiter)
                            .build();

                    final CSVReader csvReader = new CSVReaderBuilder(new StringReader(source))
                            .withCSVParser(csvParser)
                            .build();

                    //try to parse the csv and see if the delimiter is correct
                    try {
                        final List<String[]> allLines = csvReader.readAll();

                        final String[] keys = allLines.get(0);

                        if (keys.length > 1) {
                            lines = allLines;
                            break;
                        }
                    } catch (final IndexOutOfBoundsException | IOException | CsvException ignored) {}
                }

                //check if lines is null
                if (lines == null) return null;

                final String[] keys = lines.get(0);

                //key and value
                final HashSet<HashSet<Pair<String, String>>> testSet = new HashSet<>();

                /* gets each of the lines (string array) then maps each string
                in that array to a set of pairs with the key and value and adds it to the set */
                IntStream.rangeClosed(1, lines.size() - 1)
                        .mapToObj(lines::get)
                        .map(line -> IntStream.range(0, line.length)
                                .mapToObj(i -> new Pair<>(keys[i], line[i]))
                                .collect(Collectors.toCollection(HashSet::new)))
                        .forEach(testSet::add);

                //predicate to check if the set contains the term
                final Predicate<HashSet<Pair<String, String>>> setPredicate = set -> set.stream()
                        .anyMatch(pair -> {
                            if (pair.t().equalsIgnoreCase(method == Method.CUSTOM ? customString : method.getMethodName())) {
                                final String value = (boolean) configMap.get("ignorecase") ? pair.s().toLowerCase() : pair.s();
                                final String sensitiveTerm = (boolean) configMap.get("ignorecase") ? term.toLowerCase() : term;

                                return (boolean) configMap.get("contains") ? value.contains(sensitiveTerm) : sensitiveTerm.equals(value);
                            }

                            return false;
                        });

                final HashSet<JSONObject> objects = new HashSet<>();

                //filter the original set and add the filtered set to the objects set to return
                testSet.stream().filter(setPredicate).forEach(set -> set.forEach(pair -> {
                    if (pair.t().isBlank() || pair.s().isBlank()) {
                        //check if the set contains a duplicate key
                        if (objects.stream().anyMatch(jsonObject -> jsonObject.has(pair.t()))
                                || (pair.t().isBlank() && pair.s().isBlank()))
                            return;
                    }

                    final JSONObject jsonObject = new JSONObject();

                    jsonObject.put(pair.t(), pair.s());

                    objects.add(jsonObject);
                }));

                return objects;
            }
            case TXT -> {
                //split the source by new line and trim it (removes unnecessary leading and trailing whitespace)
                final HashSet<String> lines = Arrays.stream(source.split("\n"))
                        .map(String::trim)
                        .collect(Collectors.toCollection(HashSet::new));

                final HashSet<JSONObject> objects = new HashSet<>();

                for (String line : lines) {
                    //check if the line contains the term
                    if (line.toLowerCase().contains(term.toLowerCase())) {
                        //unwrap quotations when necessary
                        if ((boolean) configMap.get("unwrap")) {
                            for (String wrapping : WRAPPINGS) {
                                if (line.contains(wrapping)) {
                                    final boolean canUnwrap = StringUtils.countMatches(line, wrapping) % 2 == 0;

                                    if (canUnwrap) line = line.replace(wrapping, "");
                                }
                            }
                        }

                        //split the line by the delimiter (a regular expression/regex which matches one or more whitespaces)
                        List<String> splitLine = Arrays.asList(line.split("\\s+"));

                        //if the line is not able to be split try other delimiters
                        if (splitLine.size() <= 1) {
                            final List<String> triedDelimiters = tryDelimiters(line);

                            //update the original list
                            if (!triedDelimiters.isEmpty())
                                splitLine = triedDelimiters;
                        }

                        final JSONObject jsonObject = new JSONObject();

                        final JSONArray ipArray = new JSONArray(),
                                emailArray = new JSONArray(),
                                otherValueArray = new JSONArray(),
                                possibleDelimiterArray = new JSONArray();

                        //iterate through the split line and add the values to the appropriate array (using validation)
                        splitLine.forEach(s -> {
                            if (isIP(s))
                                ipArray.put(s);
                            else if (isEmail(s))
                                emailArray.put(s);
                            else if (OTHER_DELIMITERS.contains(s.trim()))
                                possibleDelimiterArray.put(s);
                            else
                                otherValueArray.put(s);
                        });

                        if (!ipArray.isEmpty()) jsonObject.put("IP", ipArray);
                        if (!emailArray.isEmpty()) jsonObject.put("Email", emailArray);
                        if (!otherValueArray.isEmpty()) jsonObject.put("Other Values", otherValueArray);
                        if (!possibleDelimiterArray.isEmpty()) jsonObject.put("Possible Delimiters", possibleDelimiterArray);

                        objects.add(jsonObject);
                    }
                }

                return objects;
            }
        }

        return null;
    }

    /**
     * Tries to find a working delimiter in a string
     *
     * @param line the string to search
     * @return a list of strings split by the delimiter
     */
    private static List<String> tryDelimiters(String line) {
        List<String> finalSplitLine = new ArrayList<>();

        for (String delimiter : OTHER_DELIMITERS) {
            if (line.contains(delimiter)) {
                final List<String> splitLine = Arrays.asList(line.split(delimiter));

                //check if the delimiter is able to split the line into more than one string
                if (splitLine.size() > 1) {
                    finalSplitLine = splitLine;
                    break;
                }
            }
        }

        return finalSplitLine;
    }

    private enum Method {
        USERNAME("username"),
        EMAIL("email"),
        UID("uid"),
        IP("ip"),
        CUSTOM("custom");

        private final String methodName;

        Method(final String methodName) {
            this.methodName = methodName;
        }

        public String getMethodName() {
            return methodName;
        }
    }

    private enum FileType {
        JSON,
        CSV,
        TXT
    }

    public record DatabaseCallable(String url) implements Callable<Tuple<String, Integer, FileType>> {

        @Override
        public Tuple<String, Integer, FileType> call() throws Exception {
            final StringBuilder stringBuilder = new StringBuilder();

            //had to use list bc set cannot contain duplicates
            final List<String> lines = url.startsWith("http") ?
                    new BufferedReader(new InputStreamReader(new URL(url).openStream())).lines().toList() :
                    readFileLines(url);

            //faster than using streams to load
            for (final String line : lines)
                stringBuilder.append(line).append("\n");

            final FileType fileType = isCSV(stringBuilder.toString(), url) ?
                    FileType.CSV : isJSON(stringBuilder.toString()) ? FileType.JSON : FileType.TXT;

            System.out.println(fileType);

            return new Tuple<>(stringBuilder.toString(), lines.size(), fileType);
        }

        /**
         * cannot use Files.readAllLines as it throws malformedinputexception due to different character encoding
         * StandardCharsets.ISO_8859_1 might work idk too lazy to test
         * @param path path to file
         * @return list of lines
         */
        private List<String> readFileLines(final String path) {
            final List<String> lines = new ArrayList<>();

            try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8))) {
                String line;
                while ((line = bufferedReader.readLine()) != null)
                    lines.add(line);
            } catch (final IOException e) {
                e.printStackTrace();
            }

            return lines;
        }
    }

    public record Tuple<T, S, U>(T t, S s, U u) {}

    public record Pair<T, S>(T t, S s) {}

}