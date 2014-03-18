package ro.adma;

import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.StringUtils;
import org.reflections.Reflections;
import org.reflections.ReflectionsException;
import org.reflections.scanners.Scanner;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeElementsScanner;
import org.reflections.serializers.JavaCodeSerializer;
import org.reflections.serializers.Serializer;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;
import ro.appenigne.web.framework.annotation.RequiredType;
import ro.appenigne.web.framework.annotation.UrlPattern;
import ro.appenigne.web.framework.servlet.AbstractIController;
import ro.appenigne.web.framework.utils.AbstractUserType;

import javax.servlet.http.HttpServlet;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

@Mojo(name = "web_mapping", defaultPhase = LifecyclePhase.PROCESS_RESOURCES)
public class WebXmlMojo extends AbstractMojo {

    @Parameter
    private String scanners;

    private static final String DEFAULT_INCLUDE_EXCLUDE = "-java\\..*, -javax\\..*, -sun\\..*, -com\\.sun\\..*, -com\\.google\\..*, +javax\\.servlet\\.http\\..*";

    @Parameter(defaultValue = DEFAULT_INCLUDE_EXCLUDE)
    private String includeExclude;

    @Parameter
    private String destinations;

    @Parameter
    private String extendedClass;

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

        if (StringUtils.isEmpty(extendedClass)) {
            extendedClass = AbstractIController.class.getName();
        }
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

        config.setScanners(!StringUtils.isEmpty(scanners) ? parseScanners() : new Scanner[]{new SubTypesScanner(), new AnnotationScanner()});

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
        Set<String> resources = reflections.getStore().getSubTypesOf(extendedClass);
        Set<String> resourcesHttp = reflections.getStore().getSubTypesOf(HttpServlet.class.getName());
        Multimap<String, String> annotationScanner = reflections.getStore().getOrCreate("AnnotationScanner");


        getLog().info("------------------------------------------------------------------------");
        getLog().info("Number of classes that extend " + extendedClass + ": " + resources.size());

        String fileNameWebXml = destinations + "webapp/WEB-INF/web.xml";
        String fileNameAppengineWebXml = destinations + "webapp/WEB-INF/appengine-web.xml";
        String contentWebXml = "";
        try {
            contentWebXml = readFile(fileNameWebXml);
        } catch (IOException e) {
            e.printStackTrace();
        }

        String firstPartWebXml = contentWebXml.substring(0, contentWebXml.indexOf(startMark) + startMark.length());
        String lastPartWebXml = contentWebXml.substring(contentWebXml.indexOf(endMark));

        String contentAppengineWebXml = "";
        try {
            contentAppengineWebXml = readFile(fileNameAppengineWebXml);
        } catch (IOException e) {
            e.printStackTrace();
        }

        String firstPartAppengineWebXml = contentAppengineWebXml.substring(0, contentAppengineWebXml.indexOf(startMark) + startMark.length());
        String lastPartAppengineWebXml = contentAppengineWebXml.substring(contentAppengineWebXml.indexOf(endMark));

        StringBuilder strWebXml = new StringBuilder();
        strWebXml.append(ls);
        StringBuilder strAppengineWebXml = new StringBuilder();
        strAppengineWebXml.append(ls);
        int urlPatternCounter = 0;
        int extendedClassesSkipped = 0;
        int securityConstraintCounter = 0;

        List<String> res = new ArrayList<String>(resources);
        Collections.sort(res);
        List<String> resHttp = new ArrayList<String>(resourcesHttp);
        Collections.sort(resHttp);
        for (String className : res) {
            Collection<String> urlPatterns = annotationScanner.get(className + "|" + UrlPattern.class.getName());
            if (urlPatterns.size() == 0 && !className.contains("controller")) {
                //getLog().info("Servlet mapping skipped: " + className);
                //extendedClassesSkipped++;
                continue;
            }
            if (urlPatterns.size() == 0) {
                String urlPattern = className.replaceAll("[.]", "/");
                urlPattern = "/do/" + urlPattern.substring(urlPattern.indexOf("controller/") + "controller/".length());
                urlPatterns.add(urlPattern);
            }

            if (urlPatterns.size() > 0) {
                addSystemProperty(ls, strAppengineWebXml, className, urlPatterns.toArray(new String[urlPatterns.size()]));
                //urlPatternCounter += urlPatterns.size();
            }
        }
        for (String className : resHttp) {
            String servletName = className.replaceAll("[.]", "_");
            Collection<String> urlPatterns = annotationScanner.get(className + "|" + UrlPattern.class.getName());
            if (urlPatterns.size() == 0 && !className.contains("controller")) {
                getLog().info("Servlet mapping skipped: " + className);
                extendedClassesSkipped++;
                continue;
            }
            if (urlPatterns.size() == 0) {
                String urlPattern = className.replaceAll("[.]", "/");
                urlPattern = "/do/" + urlPattern.substring(urlPattern.indexOf("controller/") + "controller/".length());
                urlPatterns.add(urlPattern);
            }

            if (urlPatterns.size() > 0) {
                addServletMapping(ls, strWebXml, className, servletName, urlPatterns.toArray(new String[urlPatterns.size()]));
                urlPatternCounter += urlPatterns.size();
            }
        }
        for (String className : res) {
            String servletName = className.replaceAll("[.]", "_");
            Collection<String> urlPatterns = annotationScanner.get(className + "|" + UrlPattern.class.getName());
            if (urlPatterns.size() == 0 && !className.contains("controller")) {
                continue;
            }
            if (urlPatterns.size() == 0) {
                String urlPattern = className.replaceAll("[.]", "/");
                urlPattern = "/do/" + urlPattern.substring(urlPattern.indexOf("controller/") + "controller/".length());
                urlPatterns.add(urlPattern);
            }
            Collection<String> requiredType = annotationScanner.get(className + "|" + RequiredType.class.getName());
            if (requiredType.size() == 1) {
                if (requiredType.iterator().next().equals(AbstractUserType.SuperAdministrator)) {
                    //create security constraint with admin
                    //addSecurityMapping(ls, strWebXml, servletName, urlPatterns.toArray(new String[urlPatterns.size()]));
                    securityConstraintCounter += urlPatterns.size();
                }
            }
            //addServletMapping(ls, str, className, servletName, urlPattern);
            //urlPatternCounter++;
        }
        try {
            writeFile(fileNameWebXml, firstPartWebXml + strWebXml.toString() + lastPartWebXml);
            writeFile(fileNameAppengineWebXml, firstPartAppengineWebXml + strAppengineWebXml.toString() + lastPartAppengineWebXml);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        getLog().info("Number of servlet mapping generated: " + urlPatternCounter);
        getLog().info("Number of servlet mapping skipped: " + extendedClassesSkipped);
        getLog().info("Number of security constraints generated: " + securityConstraintCounter);
        getLog().info("------------------------------------------------------------------------");
        //System.out.println(firstPart + str.toString() + lastPart);
        //"webapp/reflections.xml"
        //JavaCodeSerializer javaCodeSerializer = new JavaCodeSerializer();
        //javaCodeSerializer.save(reflections, destinations.trim() + "java/ro.adma.MyModelStore");

    }

    private void addSystemProperty(String ls, StringBuilder str, String className, String... urlPatterns) {
        //example: <property name="url:/enrol/{clientName}/{clientHash}" value="true"/>
        for (String urlPattern : urlPatterns) {
            str.append("<property name=\"url:");
            str.append(urlPattern);
            str.append("\" value=\"");
            str.append(className);
            str.append("\"/>");
            str.append(ls);
        }
    }

    private void addSecurityMapping(String ls, StringBuilder str, String servletName, String... urlPatters) {
        str.append("<security-constraint>");
        str.append("<web-resource-collection>");
        str.append("<web-resource-name>");
        str.append(servletName);
        str.append("</web-resource-name>");
        for (String urlPatter : urlPatters) {
            str.append("<url-pattern>");
            str.append(urlPatter);
            str.append("</url-pattern>");
        }
        str.append("</web-resource-collection>");
        str.append("<auth-constraint>");
        str.append("<role-name>admin</role-name>");
        str.append("</auth-constraint>");
        str.append("</security-constraint>");
        str.append(ls);
    }

    private void addServletMapping(String ls, StringBuilder str, String className, String servletName, String... urlPatterns) {
        str.append("<servlet>");
        str.append("<servlet-name>");
        str.append(servletName);
        str.append("</servlet-name>");
        str.append("<servlet-class>");
        str.append(className);
        str.append("</servlet-class>");
        str.append("</servlet>");

        //str.append(ls);
        for (String urlPattern : urlPatterns) {
            str.append("<servlet-mapping>");
            str.append("<servlet-name>");
            str.append(servletName);
            str.append("</servlet-name>");
            str.append("<url-pattern>");
            str.append(urlPattern);
            str.append("</url-pattern>");
            str.append("</servlet-mapping>");
        }
        str.append(ls);
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
        urls.addAll(parseOutputDirUrl());
        /*try {
            String fileSeparator = System.getProperty("file.separator");
            String outputDir = parseOutputDirUrl().toString();
            outputDir += fileSeparator + "WEB-INF" + fileSeparator + "lib";
            urls.add(new URL(outputDir + fileSeparator + "web-framework-0.2.jar"));
        } catch (MalformedURLException e) {
            getLog().error("", e);
        }*/
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

    private List<URL> parseOutputDirUrl() throws MojoExecutionException {
        try {
            List<URL> urls = new ArrayList<URL>();
            File libDir = new File(resolveLibDirectory());
            if (libDir.isDirectory()) {
                FilenameFilter fnf = new FilenameFilter() {
                    public boolean accept(File dir, String name) {
                        if (name.lastIndexOf('.') > 0) {
                            // get last index for '.' char
                            int lastIndex = name.lastIndexOf('.');

                            // get extension
                            String str = name.substring(lastIndex);

                            // match path name extension
                            if (str.equals(".jar")) {
                                return true;
                            }
                        }
                        return false;
                    }
                };
                for (File file : libDir.listFiles(fnf)) {
                    urls.add(file.toURI().toURL());
                }
            }

            urls.add(new File(resolveClassDirectory()).toURI().toURL());
            return urls;
        } catch (MalformedURLException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private String resolveOutputDirectory() {
        getLog().error(mavenProject.getBuild().getDirectory() + System.getProperty("file.separator") + mavenProject.getBuild().getFinalName());
        return mavenProject.getBuild().getDirectory() + System.getProperty("file.separator") + mavenProject.getBuild().getFinalName();
    }

    private String resolveLibDirectory() {
        String fs = System.getProperty("file.separator");
        String path = mavenProject.getBuild().getDirectory() + fs + mavenProject.getBuild().getFinalName() + fs;
        path += "WEB-INF" + fs + "lib";
        getLog().info(path);
        return path;
    }

    private String resolveClassDirectory() {
        getLog().info(mavenProject.getBuild().getOutputDirectory());
        return mavenProject.getBuild().getOutputDirectory();
    }

    private String resolveOutputWebXml() {
        String fs = System.getProperty("file.separator");
        //String path = mavenProject.getBuild().getDirectory() + fs + mavenProject.getBuild().getFinalName() + fs;
        return mavenProject.getBasedir().getAbsolutePath() + "/src/main/";
    }
}
