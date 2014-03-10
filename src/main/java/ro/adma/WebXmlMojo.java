package ro.adma;

import com.google.common.collect.Sets;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.StringUtils;
import org.reflections.Reflections;
import org.reflections.ReflectionsException;
import org.reflections.scanners.Scanner;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeAnnotationsScanner;
import org.reflections.scanners.TypeElementsScanner;
import org.reflections.serializers.JavaCodeSerializer;
import org.reflections.serializers.Serializer;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

@Mojo(name="reflections")
public class WebXmlMojo extends AbstractMojo {

    @Parameter
    private String scanners;

    private static final String DEFAULT_INCLUDE_EXCLUDE = "-java\\..*, -javax\\..*, -sun\\..*, -com\\.sun\\..*";

    @Parameter(defaultValue = DEFAULT_INCLUDE_EXCLUDE)
    private String includeExclude;

    @Parameter
    private String destinations;

    @Parameter
    private String serializer;

    @Parameter(defaultValue = "false")
    private Boolean parallel;

    @Parameter(defaultValue = "false")
    private boolean tests;

    @Parameter(property = "project", required = true, readonly = true)
    private MavenProject mavenProject;

    public WebXmlMojo() {
    }

    public void execute() throws MojoExecutionException, MojoFailureException {
        if (StringUtils.isEmpty(destinations)) {
            destinations = resolveOutputWebXml();
        }

        String outputDirectory = resolveOutputDirectory();
        if (!new File(outputDirectory).exists()) {
            getLog().warn(String.format("Reflections plugin is skipping because %s was not found", outputDirectory));
            return;
        }

        //
        ConfigurationBuilder config = new ConfigurationBuilder();

        config.setUrls(parseUrls());

        if (!StringUtils.isEmpty(includeExclude)) {
            config.filterInputsBy(FilterBuilder.parse(includeExclude));
        }

        config.setScanners(!StringUtils.isEmpty(scanners) ? parseScanners() : new Scanner[]{new SubTypesScanner(), new TypeAnnotationsScanner()});

        if (!StringUtils.isEmpty(serializer)) {
            try {
                Serializer serializerInstance = (Serializer) forName(serializer, "org.reflections.serializers").newInstance();
                config.setSerializer(serializerInstance);

                if (serializerInstance instanceof JavaCodeSerializer) {
                    int size = config.getScanners().size();
                    config.addScanners(new TypeElementsScanner());
                    if (size != config.getScanners().size()) {
                        getLog().info("added type scanners for JavaCodeSerializer");
                    }
                }
            } catch (Exception ex) {
                throw new ReflectionsException("could not create serializer instance", ex);
            }
        }

        if (parallel != null && parallel.equals(Boolean.TRUE)) {
            config.useParallelExecutor();
        }

        //
        if (Reflections.log == null) {
            try {
                Reflections.log = new MavenLogAdapter(getLog());
            } catch (Error e) {
                //ignore
            }
        }
        Reflections reflections = new Reflections(config);

        final String startMark = "<!-- Generated servlet mapping -->";
        final String endMark = "<!-- End Generated servlet mapping -->";
        final String ls = System.getProperty("line.separator");

        Set<String> resources = reflections.getStore().getSubTypesOf("ro.adma.IController");
        getLog().info("------------------------------------------------------------------------");
        getLog().info("Number of classes that extend ro.adma.IController: " + resources.size());

        String fileName = destinations + "WEB-INF/web.xml";
        String content = "";
        try {
            content = readFile(fileName);
        } catch (IOException e) {
            e.printStackTrace();
        }

        String firstPart = content.substring(0, content.indexOf(startMark) + startMark.length());
        String lastPart = content.substring(content.indexOf(endMark));
        StringBuilder str = new StringBuilder();
        str.append(ls);
        int counter = 0;
        int skipped = 0;
        for (String className : resources) {
            String servletName = className.replaceAll("[.]", "_");
            if (!className.contains("controller")) {
                getLog().info("Servlet mapping skipped: " + className);
                skipped++;
                continue;
            }
            String urlPattern = className.replaceAll("[.]", "/");
            urlPattern = urlPattern.substring(urlPattern.indexOf("controller/") + "controller/".length());

            str.append("<servlet>");
            str.append("<servlet-name>");
            str.append(servletName);
            str.append("</servlet-name>");
            str.append("<servlet-class>");
            str.append(className);
            str.append("</servlet-class>");
            str.append("</servlet>");
            str.append("<servlet-mapping>");
            //str.append(ls);
            str.append("<servlet-name>");
            str.append(servletName);
            str.append("</servlet-name>");
            str.append("<url-pattern>/do/");
            str.append(urlPattern);
            str.append("</url-pattern>");
            str.append("</servlet-mapping>");
            str.append(ls);
            counter++;
        }
        try {
            writeFile(fileName, firstPart + str.toString() + lastPart);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        getLog().info("Number of servlet mapping generated: " + counter);
        getLog().info("Number of servlet mapping skipped: " + skipped);
        getLog().info("------------------------------------------------------------------------");
        //System.out.println(firstPart + str.toString() + lastPart);
    }

    private static String readFile(String file) throws IOException {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(file), "UTF8"));
        String line;
        StringBuilder stringBuilder = new StringBuilder();
        String ls = System.getProperty("line.separator");

        while ((line = in.readLine()) != null) {
            stringBuilder.append(line);
            stringBuilder.append(ls);
        }

        return stringBuilder.toString();
    }

    private static void writeFile(String fileName, String content) throws FileNotFoundException, UnsupportedEncodingException {
        PrintWriter writer = new PrintWriter(fileName, "UTF-8");
        writer.print(content);
        writer.close();
    }

    private Set<URL> parseUrls() throws MojoExecutionException {
        final Set<URL> urls = Sets.newHashSet();
        urls.add(parseOutputDirUrl());

        if (!StringUtils.isEmpty(includeExclude)) {
            for (String string : includeExclude.split(",")) {
                String trimmed = string.trim();
                char prefix = trimmed.charAt(0);
                String pattern = trimmed.substring(1);
                if (prefix == '+') {
                    urls.addAll(ClasspathHelper.forPackage(pattern));
                }
            }
        }

        return urls;
    }

    private Scanner[] parseScanners() throws MojoExecutionException {
        Set<Scanner> scannersSet = new HashSet<Scanner>(0);

        if (StringUtils.isNotEmpty(scanners)) {
            String[] scannerClasses = scanners.split(",");
            for (String scannerClass : scannerClasses) {
                try {
                    scannersSet.add((Scanner) forName(scannerClass.trim(), "org.reflections.scanners").newInstance());
                } catch (Exception e) {
                    throw new MojoExecutionException(String.format("error getting scanner %s or org.reflections.scanners.%s", scannerClass.trim(), scannerClass.trim()), e);
                }
            }
        }

        return scannersSet.toArray(new Scanner[scannersSet.size()]);
    }

    @SuppressWarnings({"unchecked"})
    private static <T> Class<T> forName(String name, String... prefixes) throws ClassNotFoundException {
        try {
            return (Class<T>) Class.forName(name.trim());
        } catch (Exception e) {
            if (prefixes != null) {
                for (String prefix : prefixes) {
                    try {
                        return (Class<T>) Class.forName(prefix + "." + name.trim());
                    } catch (Exception e1) { /*ignore*/ }
                }
            }
        }
        throw new ClassNotFoundException(name);
    }

    private URL parseOutputDirUrl() throws MojoExecutionException {
        try {
            File outputDirectoryFile = new File(resolveOutputDirectory() + '/');
            return outputDirectoryFile.toURI().toURL();
        } catch (MalformedURLException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private String resolveOutputDirectory() {
        return mavenProject.getBuild().getOutputDirectory();
    }

    private String resolveOutputWebXml() {

        return mavenProject.getBasedir().getAbsolutePath() + "/src/main/webapp/";
    }
}
