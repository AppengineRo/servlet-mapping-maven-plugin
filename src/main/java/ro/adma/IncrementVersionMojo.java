package ro.adma;

import com.google.common.collect.Sets;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.StringUtils;
import org.reflections.scanners.Scanner;
import org.reflections.util.ClasspathHelper;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Mojo(name = "increment_version", defaultPhase = LifecyclePhase.PREPARE_PACKAGE)
public class IncrementVersionMojo extends AbstractMojo {


    @Parameter
    private String destinations;

    @Parameter(property = "project", required = true, readonly = true)
    private MavenProject mavenProject;

    public IncrementVersionMojo() {
    }

    public void execute() throws MojoExecutionException, MojoFailureException {

        if (StringUtils.isEmpty(destinations)) {
            destinations = resolveOutputWebXml();
        }


        final String startMark = "<version>";
        final String endMark = "</version>";
        final String ls = System.getProperty("line.separator");


        String fileNameAppengineWebXml = destinations + "WEB-INF/appengine-web.xml";

        String contentAppengineWebXml = "";
        try {
            contentAppengineWebXml = readFile(fileNameAppengineWebXml);
        } catch (IOException e) {
            e.printStackTrace();
        }
        String appId = contentAppengineWebXml.substring(contentAppengineWebXml.indexOf("<application>") + "<application>".length(), contentAppengineWebXml.indexOf("</application>"));
        String oldVersion = contentAppengineWebXml.substring(contentAppengineWebXml.indexOf("<version>") + "<version>".length(), contentAppengineWebXml.indexOf("</version>"));
        String module = "default";
        if (contentAppengineWebXml.contains("<module>")) {
            module = contentAppengineWebXml.substring(contentAppengineWebXml.indexOf("<module>") + "<module>".length(), contentAppengineWebXml.indexOf("</module>"));
        }

        getLog().warn("appID: " + appId);
        getLog().warn("module: " + module);
        String firstPartAppengineWebXml = contentAppengineWebXml.substring(0, contentAppengineWebXml.indexOf(startMark) + startMark.length());
        String lastPartAppengineWebXml = contentAppengineWebXml.substring(contentAppengineWebXml.indexOf(endMark));
        try {
            URL url = new URL("https://" + appId + ".appspot.com/getMajorVersion/" + module + "/");
            URLConnection urlConnection = url.openConnection();
            urlConnection.connect();
            InputStream inputStream = urlConnection.getInputStream();
            String strAppengineWebXml = readInput(inputStream).trim();
            int versionNumber = Integer.parseInt(strAppengineWebXml);
            versionNumber++;
            getLog().warn("Version number changed from: " + strAppengineWebXml + " to: " + versionNumber);
            writeFile(fileNameAppengineWebXml, firstPartAppengineWebXml + versionNumber + lastPartAppengineWebXml);
        } catch (FileNotFoundException e) {
            getLog().warn(e);
        } catch (UnsupportedEncodingException e) {
            getLog().warn(e);
        } catch (MalformedURLException e) {
            getLog().warn(e);
        } catch (IOException e) {
            getLog().warn(e);
        } catch (NumberFormatException e) {
            getLog().error("Version number was not changed: " + oldVersion);
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
