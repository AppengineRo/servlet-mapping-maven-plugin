package ro.adma;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.StringUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.MessageFormat;

@Mojo(name = "increment_version", defaultPhase = LifecyclePhase.PREPARE_PACKAGE)
public class IncrementVersionMojo extends AbstractMojo {


    @Parameter
    private String destinations;

    @Parameter(defaultValue = "")
    private String suffix;

    @Parameter(defaultValue = "")
    private String prefix;

    @Parameter(defaultValue = "https://{0}.appspot.com/GetMajorVersion?module={1}")
    private String url;

    @Parameter(property = "project", required = true, readonly = true)
    private MavenProject mavenProject;

    public IncrementVersionMojo() {
    }

    public void execute() throws MojoExecutionException, MojoFailureException {

        if (StringUtils.isEmpty(destinations)) {
            destinations = resolveOutputWebXml();
        }
        if (StringUtils.isEmpty(suffix)) {
            suffix = "";
        }

        if (StringUtils.isEmpty(prefix)) {
            prefix = "";
        }

        final String versionStartMark = "<version>";
        final String versionEndMark = "</version>";

        final String applicationStartMark = "<application>";
        final String applicationEndMark = "</application>";

        final String moduleStartMark = "<module>";
        final String moduleEndMark = "</module>";

        final String ls = System.getProperty("line.separator");

        String fileNameAppengineWebXml = destinations + "WEB-INF/appengine-web.xml";

        String contentAppengineWebXml;
        try {
            contentAppengineWebXml = readFile(fileNameAppengineWebXml);
        } catch (IOException e) {
            getLog().warn(e);
            throw new MojoFailureException(e.getMessage());
        }
        String appId = contentAppengineWebXml.substring(contentAppengineWebXml.indexOf(applicationStartMark) + applicationStartMark.length(), contentAppengineWebXml.indexOf(applicationEndMark));
        String module = "default";
        if (contentAppengineWebXml.contains(moduleStartMark)) {
            module = contentAppengineWebXml.substring(contentAppengineWebXml.indexOf(moduleStartMark) + moduleStartMark.length(), contentAppengineWebXml.indexOf(moduleEndMark));
        }
        getLog().info("appID: " + appId);
        getLog().info("module: " + module);

        if (!contentAppengineWebXml.contains(versionStartMark)) {
            String firstPartAppengineWebXml;
            String lastPartAppengineWebXml;
            if (contentAppengineWebXml.contains(moduleStartMark)) {
                // we insert version after module
                firstPartAppengineWebXml = contentAppengineWebXml.substring(0, contentAppengineWebXml.indexOf(moduleEndMark) + moduleEndMark.length());
                lastPartAppengineWebXml = contentAppengineWebXml.substring(contentAppengineWebXml.indexOf(moduleEndMark) + moduleEndMark.length());
            } else {
                //we insert version after application
                firstPartAppengineWebXml = contentAppengineWebXml.substring(0, contentAppengineWebXml.indexOf(applicationEndMark) + applicationEndMark.length());
                lastPartAppengineWebXml = contentAppengineWebXml.substring(contentAppengineWebXml.indexOf(applicationEndMark));
            }
            firstPartAppengineWebXml += ls + "    " + versionStartMark;
            lastPartAppengineWebXml = versionEndMark + ls + lastPartAppengineWebXml;


            String fullLink = MessageFormat.format(url, appId, module);
            String defaultVersionNumber = "none";
            int versionNumber = 1;
            try {
                URL uri = new URL(fullLink);
                HttpURLConnection urlConnection = (HttpURLConnection) uri.openConnection();

                urlConnection.connect();
                int responseCode = urlConnection.getResponseCode();
                if (responseCode >= 200 && responseCode < 300) {
                    InputStream inputStream = urlConnection.getInputStream();
                    defaultVersionNumber = readInput(inputStream).trim().replaceAll("[^0-9]+", "");
                    versionNumber = Integer.parseInt(defaultVersionNumber);
                    versionNumber++;
                }
            } catch (NumberFormatException e) {
                throw new MojoFailureException("Version number cannot be calculated. Do you have a servlet that responds with the default module version? We tested on and couldn't parseInt it: " + fullLink);
            } catch (Exception e) {
                getLog().warn(e);
                throw new MojoFailureException(e.getMessage());
            }
            if (!prefix.isEmpty()) {
                prefix = prefix.toLowerCase().replaceAll("[^a-z]+", "") + "-";
            }
            if (!suffix.isEmpty()) {
                suffix = "-" + suffix.toLowerCase().replaceAll("[^a-z]+", "");
            }
            String versionNumberString = prefix + versionNumber + suffix;
            getLog().info("Version number changed from: " + defaultVersionNumber + " to: " + versionNumberString);
            try {
                writeFile(fileNameAppengineWebXml, firstPartAppengineWebXml + versionNumberString + lastPartAppengineWebXml);
            } catch (FileNotFoundException | UnsupportedEncodingException e) {
                getLog().warn(e);
                throw new MojoFailureException(e.getMessage());
            }

        } else {
            String oldVersion = contentAppengineWebXml.substring(contentAppengineWebXml.indexOf(versionStartMark) + versionStartMark.length(), contentAppengineWebXml.indexOf(versionEndMark));
            getLog().warn("Version specified in appengine-web.xml. Keeping that version: " + oldVersion);
        }
        //getLog().info("Number of servlet mapping generated: " + urlPatternCounter);
    }


    private static String readFile(String file) throws IOException {
        return readInput(new FileInputStream(file));

    }

    private static String readInput(InputStream inputStream) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(inputStream, "UTF8"));
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

    private String resolveOutputWebXml() {
        String fs = System.getProperty("file.separator");
        String path = mavenProject.getBuild().getDirectory() + fs + mavenProject.getBuild().getFinalName() + fs;
        //getLog().warn(path);
        //getLog().warn(mavenProject.getBasedir().getAbsolutePath() + "/src/main/webapp/");
        return path;
    }
}
