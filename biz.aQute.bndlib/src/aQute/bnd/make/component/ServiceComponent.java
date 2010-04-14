package aQute.bnd.make.component;

import java.io.*;
import java.util.*;
import java.util.regex.*;

import aQute.bnd.annotation.component.*;
import aQute.bnd.service.*;
import aQute.lib.osgi.*;
import aQute.lib.osgi.Clazz.*;
import aQute.libg.version.*;

/**
 * This class is an analyzer plugin. It looks at the properties and tries to
 * find out if the Service-Component header contains the bnd shortut syntax. If
 * not, the header is copied to the output, if it does, an XML file is created
 * and added to the JAR and the header is modified appropriately.
 */
public class ServiceComponent implements AnalyzerPlugin {
    public final static String       NAMESPACE_STEM                 = "http://www.osgi.org/xmlns/scr";
    public final static String       JIDENTIFIER                    = "<<identifier>>";
    public final static String       COMPONENT_NAME                 = "name:";
    public final static String       COMPONENT_FACTORY              = "factory:";
    public final static String       COMPONENT_SERVICEFACTORY       = "servicefactory:";
    public final static String       COMPONENT_IMMEDIATE            = "immediate:";
    public final static String       COMPONENT_ENABLED              = "enabled:";
    public final static String       COMPONENT_DYNAMIC              = "dynamic:";
    public final static String       COMPONENT_MULTIPLE             = "multiple:";
    public final static String       COMPONENT_PROVIDE              = "provide:";
    public final static String       COMPONENT_OPTIONAL             = "optional:";
    public final static String       COMPONENT_PROPERTIES           = "properties:";
    public final static String       COMPONENT_IMPLEMENTATION       = "implementation:";
    public final static String       COMPONENT_DESCRIPTORS          = ".descriptors:";

    // v1.1.0
    public final static String       COMPONENT_VERSION              = "version:";
    public final static String       COMPONENT_CONFIGURATION_POLICY = "configuration-policy:";
    public final static String       COMPONENT_MODIFIED             = "modified:";
    public final static String       COMPONENT_ACTIVATE             = "activate:";
    public final static String       COMPONENT_DEACTIVATE           = "deactivate:";

    final static Map<String, String> EMPTY                          = Collections
                                                                            .emptyMap();

    public final static String[]     componentDirectives            = new String[] {
            COMPONENT_FACTORY, COMPONENT_IMMEDIATE, COMPONENT_ENABLED,
            COMPONENT_DYNAMIC, COMPONENT_MULTIPLE, COMPONENT_PROVIDE,
            COMPONENT_OPTIONAL, COMPONENT_PROPERTIES, COMPONENT_IMPLEMENTATION,
            COMPONENT_SERVICEFACTORY, COMPONENT_VERSION,
            COMPONENT_CONFIGURATION_POLICY, COMPONENT_MODIFIED,
            COMPONENT_ACTIVATE, COMPONENT_DEACTIVATE, COMPONENT_NAME, COMPONENT_DESCRIPTORS };

    public final static Set<String>  SET_COMPONENT_DIRECTIVES       = new HashSet<String>(
                                                                            Arrays
                                                                                    .asList(componentDirectives));

    public final static Set<String>  SET_COMPONENT_DIRECTIVES_1_1   = //
                                                                    new HashSet<String>(
                                                                            Arrays
                                                                                    .asList(
                                                                                            COMPONENT_VERSION,
                                                                                            COMPONENT_CONFIGURATION_POLICY,
                                                                                            COMPONENT_MODIFIED,
                                                                                            COMPONENT_ACTIVATE,
                                                                                            COMPONENT_DEACTIVATE));

    public boolean analyzeJar(Analyzer analyzer) throws Exception {

        ComponentMaker m = new ComponentMaker(analyzer);

        Map<String, Map<String, String>> l = m.doServiceComponent();

        if (!l.isEmpty())
            analyzer.setProperty(Constants.SERVICE_COMPONENT, Processor
                    .printClauses(l, ""));

        analyzer.getInfo(m, "Service-Component");
        m.close();
        return false;
    }

    private static class ComponentMaker extends Processor {
        Analyzer analyzer;

        ComponentMaker(Analyzer analyzer) {
            super(analyzer);
            this.analyzer = analyzer;
        }

        /**
         * Check if a service component header is actually referring to a class.
         * If so, replace the reference with an XML file reference. This makes
         * it easier to create and use components. We also allow wildcards in
         * the class, they must then map to embedded classes that have
         * annotations.
         * 
         * @throws UnsupportedEncodingException
         * 
         */
        Map<String, Map<String, String>> doServiceComponent() throws Exception {
            Map<String, Map<String, String>> serviceComponents = newMap();
            String header = getProperty(SERVICE_COMPONENT);
            Map<String, Map<String, String>> sc = parseHeader(header);

            for (Map.Entry<String, Map<String, String>> entry : sc.entrySet()) {
                String name = entry.getKey();
                Map<String, String> info = entry.getValue();

                if (name.indexOf('/') >= 0 || name.endsWith(".xml")) {
                    // Normal service component, we do not process it
                    serviceComponents.put(name, info);
                } else
                    try {
                        // Try to locate any classes in the wildcarded universe
                        // that are annotated with Component.
                        Collection<Clazz> expanded = analyzer.getClasses("",
                        // Then limit the ones with component annotations.
                                QUERY.ANNOTATION.toString(), Component.class
                                        .getName(), // 
                                // Limit the scope
                                QUERY.NAMED.toString(), name //
                                );

                        if (expanded.isEmpty()
                                || Processor.isTrue(info.get(NOANNOTATIONS))) {
                            // We did not find any annotated classes
                            // Check if we import or contain the class
                            createComponentResource(name, info);
                            serviceComponents.put("OSGI-INF/" + name + ".xml",
                                    EMPTY);
                        } else {
                            for (Clazz c : expanded) {
                                // Get the component definition
                                // from the annotations
                                Map<String, String> map = ComponentAnnotationReader
                                        .getDefinition(c, this);

                                // Pick the name, the annotation can override
                                // the name.
                                String localname = map.get(COMPONENT_NAME);
                                if (localname == null)
                                    localname = c.getFQN();

                                // Overide the component info with out manifest
                                // entries. We merge the properties though.

                                String merged = Processor.merge(info
                                        .remove(COMPONENT_PROPERTIES), map
                                        .remove(COMPONENT_PROPERTIES));
                                if (merged != null && merged.length() > 0)
                                    map.put(COMPONENT_PROPERTIES, merged);
                                map.putAll(info);
                                createComponentResource(localname, map);
                                serviceComponents.put("OSGI-INF/" + localname
                                        + ".xml", EMPTY);
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        error(
                                "Invalid Service-Component header: %s %s, throws %s",
                                name, info, e);
                    }
            }
            return serviceComponents;
        }

        private void createComponentResource(String name,
                Map<String, String> info) throws IOException {
            // We can override the name in the parameters
            if (info.containsKey(COMPONENT_NAME))
                name = info.get(COMPONENT_NAME);

            // Assume the impl==name, but allow override
            String impl = name;
            if (info.containsKey(COMPONENT_IMPLEMENTATION))
                impl = info.get(COMPONENT_IMPLEMENTATION);

            // Check if such a class exists
            if (!analyzer.checkClass(impl))
                error("No implementation found for Service-Component entry: "
                        + impl);

            // We have a definition, so make an XML resources
            Resource resource = createComponentResource(name, impl, info);
            analyzer.getJar()
                    .putResource("OSGI-INF/" + name + ".xml", resource);
        }

        /**
         * Create the resource for a DS component.
         * 
         * @param list
         * @param name
         * @param info
         * @throws UnsupportedEncodingException
         */
        Resource createComponentResource(String name, String impl,
                Map<String, String> info) throws IOException {
            String namespace = getNamespace(info);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintWriter pw = new PrintWriter(new OutputStreamWriter(out,
                    "UTF-8"));
            pw.println("<?xml version='1.0' encoding='utf-8'?>");
            pw.print("<component name='" + name + "'");
            if (namespace != null) {
                pw.print(" xmlns='" + namespace + "'");
            }

            doAttribute(pw, info.get(COMPONENT_FACTORY), "factory");
            doAttribute(pw, info.get(COMPONENT_IMMEDIATE), "immediate",
                    "false", "true");
            doAttribute(pw, info.get(COMPONENT_ENABLED), "enabled", "true",
                    "false");
            doAttribute(pw, info.get(COMPONENT_CONFIGURATION_POLICY),
                    "configuration-policy", "optional", "require", "ignore");
            doAttribute(pw, info.get(COMPONENT_ACTIVATE), "activate",
                    JIDENTIFIER);
            doAttribute(pw, info.get(COMPONENT_DEACTIVATE), "deactivate",
                    JIDENTIFIER);
            doAttribute(pw, info.get(COMPONENT_MODIFIED), "modified",
                    JIDENTIFIER);

            pw.println(">");

            // Allow override of the implementation when people
            // want to choose their own name
            pw.println("  <implementation class='"
                    + (impl == null ? name : impl) + "'/>");

            String provides = info.get(COMPONENT_PROVIDE);
            boolean servicefactory = Processor.isTrue(info
                    .get(COMPONENT_SERVICEFACTORY));

            if (servicefactory
                    && Processor.isTrue(info.get(COMPONENT_IMMEDIATE))) {
                // TODO can become error() if it is up to me
                warning(
                        "For a Service Component, the immediate option and the servicefactory option are mutually exclusive for %(%s)",
                        name, impl);
            }
            provide(pw, provides, servicefactory, impl);
            properties(pw, info);
            reference(info, pw);
            pw.println("</component>");
            pw.close();
            byte[] data = out.toByteArray();
            out.close();
            return new EmbeddedResource(data, 0);
        }

        private void doAttribute(PrintWriter pw, String value, String name,
                String... matches) {
            if (value != null) {
                if (matches.length != 0) {
                    if (matches.length == 1 && matches[0].equals(JIDENTIFIER)) {
                        if (!Verifier.isIdentifier(value))
                            error(
                                    "Component attribute %s has value %s but is not a Java identifier",
                                    name, value);
                    } else {

                        if (!Verifier.isMember(value, matches))
                            error(
                                    "Component attribute %s has value %s but is not a member of %s",
                                    name, value, Arrays.toString(matches));
                    }
                }
                pw.print(" ");
                pw.print(name);
                pw.print("='");
                pw.print(value);
                pw.print("'");
            }
        }

        /**
         * Check if we need to use the v1.1 namespace (or later).
         * 
         * @param info
         * @return
         */
        private String getNamespace(Map<String, String> info) {
            String version = info.get(COMPONENT_VERSION);
            if (version != null) {
                try {
                    Version v = new Version(version);
                    return NAMESPACE_STEM + "/v" + v;
                } catch (Exception e) {
                    error("version: specified on component header but not a valid version: "
                            + version);
                    return null;
                }
            }
            for (String key : info.keySet()) {
                if (SET_COMPONENT_DIRECTIVES_1_1.contains(key)) {
                    return NAMESPACE_STEM + "/v1.1.0";
                }
            }
            return null;
        }

        /**
         * Print the Service-Component properties element
         * 
         * @param pw
         * @param info
         */
        void properties(PrintWriter pw, Map<String, String> info) {
            Collection<String> properties = split(info
                    .get(COMPONENT_PROPERTIES));
            for (Iterator<String> p = properties.iterator(); p.hasNext();) {
                String clause = p.next();
                int n = clause.indexOf('=');
                if (n <= 0) {
                    error("Not a valid property in service component: "
                            + clause);
                } else {
                    String type = null;
                    String name = clause.substring(0, n);
                    if (name.indexOf('@') >= 0) {
                        String parts[] = name.split("@");
                        name = parts[1];
                        type = parts[0];
                    } else if (name.indexOf(':') >= 0) {
                        String parts[] = name.split(":");
                        name = parts[0];
                        type = parts[1];
                    }
                    String value = clause.substring(n + 1).trim();
                    // TODO verify validity of name and value.
                    pw.print("  <property name='");
                    pw.print(name);
                    pw.print("'");

                    if (type != null) {
                        if (VALID_PROPERTY_TYPES.matcher(type).matches()) {
                            pw.print(" type='");
                            pw.print(type);
                            pw.print("'");
                        } else {
                            warning("Invalid property type '" + type
                                    + "' for property " + name);
                        }
                    }

                    String parts[] = value.split("\\s*(\\||\\n)\\s*");
                    if (parts.length > 1) {
                        pw.println(">");
                        for (String part : parts) {
                            pw.println(part);
                        }
                        pw.println("</property>");
                    } else {
                        pw.print(" value='");
                        pw.print(parts[0]);
                        pw.println("'/>");
                    }
                }
            }
        }

        /**
         * @param pw
         * @param provides
         */
        void provide(PrintWriter pw, String provides, boolean servicefactory,
                String impl) {
            if (provides != null) {
                if (!servicefactory)
                    pw.println("  <service>");
                else
                    pw.println("  <service servicefactory='true'>");

                StringTokenizer st = new StringTokenizer(provides, ",");
                while (st.hasMoreTokens()) {
                    String interfaceName = st.nextToken();
                    pw.println("    <provide interface='" + interfaceName
                            + "'/>");
                    if (!analyzer.checkClass(interfaceName))
                        error("Component definition provides a class that is neither imported nor contained: "
                                + interfaceName);

                    // TODO verifies the impl. class extends or implements the
                    // interface
                }
                pw.println("  </service>");
            } else if (servicefactory)
                warning("The servicefactory:=true directive is set but no service is provided, ignoring it");
        }

        public final static Pattern REFERENCE = Pattern
                                                      .compile("([^(]+)(\\(.+\\))?");

        /**
         * @param info
         * @param pw
         */

        void reference(Map<String, String> info, PrintWriter pw) {
            Collection<String> dynamic = new ArrayList<String>(split(info
                    .get(COMPONENT_DYNAMIC)));
            Collection<String> optional = new ArrayList<String>(split(info
                    .get(COMPONENT_OPTIONAL)));
            Collection<String> multiple = new ArrayList<String>(split(info
                    .get(COMPONENT_MULTIPLE)));

            Collection<String> descriptors = split(info
                    .get(COMPONENT_DESCRIPTORS));

            for (Map.Entry<String, String> entry : info.entrySet()) {

                // Skip directives
                String referenceName = entry.getKey();
                if (referenceName.endsWith(":")) {
                    if (!SET_COMPONENT_DIRECTIVES.contains(referenceName))
                        error("Unrecognized directive in Service-Component header: "
                                + referenceName);
                    continue;
                }

                // Parse the bind/unbind methods from the name
                // if set. They are separated by '/'
                String bind = null;
                String unbind = null;

                boolean unbindCalculated = false;

                if (referenceName.indexOf('/') >= 0) {
                    String parts[] = referenceName.split("/");
                    referenceName = parts[0];
                    bind = parts[1];
                    if (parts.length > 2) {
                        unbind = parts[2];
                    } else {
                        unbindCalculated = true;
                        if (bind.startsWith("add"))
                            unbind = bind.replaceAll("add(.+)", "remove$1");
                        else
                            unbind = "un" + bind;
                    }
                } else if (Character.isLowerCase(referenceName.charAt(0))) {
                    unbindCalculated = true;
                    bind = "set"
                            + Character.toUpperCase(referenceName.charAt(0))
                            + referenceName.substring(1);
                    unbind = "un" + bind;
                }

                String interfaceName = entry.getValue();
                if (interfaceName == null || interfaceName.length() == 0) {
                    error("Invalid Interface Name for references in Service Component: "
                            + referenceName + "=" + interfaceName);
                    continue;
                }

                // If we have descriptors, we have analyzed the component.
                // So why not check the methods
                if ( descriptors.size() > 0 ) {
                    // Verify that the bind method exists
                    if (!descriptors.contains(bind))
                        error("The bind method %s for %s not defined", bind,
                                referenceName);

                    // Check if the unbind method exists
                    if (!descriptors.contains(unbind)) {
                        if (unbindCalculated)
                            // remove it
                            unbind = null;
                        else
                            error("The unbind method %s for %s not defined",
                                    unbind, referenceName);
                    }
                }
                // Check tje cardinality by looking at the last
                // character of the value
                char c = interfaceName.charAt(interfaceName.length() - 1);
                if ("?+*~".indexOf(c) >= 0) {
                    if (c == '?' || c == '*' || c == '~')
                        optional.add(referenceName);
                    if (c == '+' || c == '*')
                        multiple.add(referenceName);
                    if (c == '+' || c == '*' || c == '?')
                        dynamic.add(referenceName);
                    interfaceName = interfaceName.substring(0, interfaceName
                            .length() - 1);
                }

                // Parse the target from the interface name
                // The target is a filter.
                String target = null;
                Matcher m = REFERENCE.matcher(interfaceName);
                if (m.matches()) {
                    interfaceName = m.group(1);
                    target = m.group(2);
                }

                if (!analyzer.checkClass(interfaceName))
                    error("Component definition refers to a class that is neither imported nor contained: "
                            + interfaceName);

                pw.printf("  <reference name='%s'", referenceName);
                pw.printf(" interface='%s'", interfaceName);

                String cardinality = optional.contains(referenceName) ? "0"
                        : "1";
                cardinality += "..";
                cardinality += multiple.contains(referenceName) ? "n" : "1";
                if (!cardinality.equals("1..1"))
                    pw.print(" cardinality='" + cardinality + "'");

                if (bind != null) {
                    pw.printf(" bind='%s'", bind);
                    if (unbind != null) {
                        pw.printf(" unbind='%s'", unbind);
                    }
                }

                if (dynamic.contains(referenceName)) {
                    pw.print(" policy='dynamic'");
                }

                if (target != null) {
                    // Filter filter = new Filter(target);
                    // if (filter.verify() == null)
                    // pw.print(" target='" + filter.toString() + "'");
                    // else
                    // error("Target for " + referenceName
                    // + " is not a correct filter: " + target + " "
                    // + filter.verify());
                    pw.print(" target='" + target + "'");
                }
                pw.println("/>");
            }
        }
    }
}
