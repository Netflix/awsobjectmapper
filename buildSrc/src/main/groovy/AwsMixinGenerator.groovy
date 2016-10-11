/*
 * Copyright 2014-2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.google.common.reflect.ClassPath
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task

import java.lang.reflect.Method
import java.util.regex.Matcher
import java.util.regex.Pattern


class AwsMixinGenerator implements Plugin<Project> {

  private final Set<String> prohibited = new HashSet<String>([
    "getGeneralProgressListener",
    "getRequestClientOptions",
    "getRequestCredentials",
    "getRequestMetricCollector",
    "setGeneralProgressListener",
    "setRequestCredentials",
    "setRequestMetricCollector",
    "getDryRunRequest",
    "setInvokeArgs"
  ])

  private void deleteDir(File dir) {
    if (dir.exists()) {
      dir.listFiles().each {
        if (it.isDirectory()) deleteDir(it) else it.delete()
      }
      dir.delete()
    }
  }

  private void setupDir(File dir) {
    deleteDir(dir)
    dir.mkdirs()
  }

  private boolean isModelClass(Class<?> c) {
    boolean skip = false

    // Skip package and exception classes
    skip = c.simpleName == "package-info" || c.simpleName.endsWith("Exception")

    // Ignore interfaces
    skip = skip || c.isInterface()

    // Must have an empty constructor
    skip = skip || !c.constructors.any { it.parameterTypes.length == 0 }

    // Must have methods to annotate
    skip = skip || methodsToAnnotate(c).isEmpty()

    return !skip
  }

  // Don't camel case if starts with multiple uppercase
  private List<String> fieldsToOverride(Class<?> c) {
    List<String> overrides = new ArrayList<String>()
    c.methods.each {
      if (it.name.startsWith("get")) {
        String field = it.name.substring(3)
        if (field ==~ /^[A-Z]{2}.*$/) overrides.add(field)
      }
    }
    return overrides
  }

  private String methodString(Method m) {
    String params = ""
    int i = 0
    m.parameterTypes.each { t ->
      params += ", ${t.name} p$i"
      i++
    }
    params = (params.length() == 0) ? params : params.substring(2)
    return "public ${m.returnType.name} ${m.name}($params)"
  }

  // Rules:
  // 1. getFoo should be ignored if isFoo is present
  // 2. setFoo(FooEnum) should be ignored, use setFoo(String s)
  private List<String> methodsToAnnotate(Class<?> c) {
    List<String> anno = new ArrayList<String>()
    Set<String> methods = new HashSet<String>()
    c.methods.each { methods.add(it.name) }
    c.methods.each {
      if (it.name.startsWith("get") && methods.contains("is${it.name.substring(3)}")) {
        anno.add("@JsonIgnore Boolean is${it.name.substring(3)}();")
      } else if (it.name.startsWith("set") && it.parameterTypes.any { cls -> cls.isEnum() || cls.getName().startsWith("java.nio") }) {
        String ptype = it.parameterTypes[0].name;
        anno.add("@JsonIgnore void ${it.name}(${ptype} v);")
        anno.add("@JsonProperty void ${it.name}(String v);")
      } else if (prohibited.contains(it.name)) {
        anno.add("@JsonIgnore ${methodString(it)};")
      }
    }

    return anno
  }

  private void createNameForField(Writer out, File dir, List<String> overrides) {
    out.writeLine("  @Override")
    out.writeLine("  public String nameForField(MapperConfig c, AnnotatedField f, String s) {")
    overrides.unique().each {
      String m = it.substring(0, 1).toLowerCase() + it.substring(1)
      out.writeLine("    if (\"${m}\".equals(f.getName())) return \"${it}\";")
    }
    out.writeLine("    return super.nameForField(c, f, s);")
    out.writeLine("  }")
  }

  private void createNameForGetterMethod(Writer out, File dir, List<String> overrides) {
    out.writeLine("  @Override")
    out.writeLine("  public String nameForGetterMethod(MapperConfig c, AnnotatedMethod m, String s) {")
    overrides.unique().each {
      out.writeLine("    if (\"get${it}\".equals(m.getName())) return \"${it}\";")
    }
    out.writeLine("    return super.nameForGetterMethod(c, m, s);")
    out.writeLine("  }")
  }

  private void createNameForSetterMethod(Writer out, File dir, List<String> overrides) {
    out.writeLine("  @Override")
    out.writeLine("  public String nameForSetterMethod(MapperConfig c, AnnotatedMethod m, String s) {")
    overrides.unique().each {
      out.writeLine("    if (\"set${it}\".equals(m.getName())) return \"${it}\";")
    }
    out.writeLine("    return super.nameForSetterMethod(c, m, s);")
    out.writeLine("  }")
  }

  private List<String> collectOverrides(Class<?> baseClass) {
    String pkg = "${baseClass.package.name}.model"
    ClassLoader cl = baseClass.classLoader
    Set<Class<?>> classes = ClassPath.from(cl).getTopLevelClasses(pkg)
    List<String> overrides = new ArrayList<String>()
    classes.each {
      overrides += fieldsToOverride(it.load())
    }
    return overrides
  }

  private void createMixin(Writer out, String prefix, Class<?> c) {
    out.writeLine(mixinHeader)
    out.writeLine("interface ${prefix}${c.simpleName}Mixin {")
    methodsToAnnotate(c).each {
      out.writeLine("  ${it}")
    }
    out.writeLine("}")
  }

  private void createMixins(Writer out, File dir, String prefix, Class<?> baseClass) {
    String pkg = "${baseClass.package.name}.model"
    ClassLoader cl = baseClass.classLoader
    Set<Class<?>> classes = ClassPath.from(cl).getTopLevelClasses(pkg)
    Set<Class<?>> matches = classes.findAll { isModelClass(it.load()) }
    matches.each {
      String mixinName = "${prefix}${it.simpleName}Mixin"
      new File(dir, "${mixinName}.java").withWriter { w ->
        createMixin(w, prefix, it.load())
      }
      out.writeLine("    objectMapper.addMixIn(${it.name}.class, ${mixinName}.class);")
    }
  }

  void apply(Project project) {
    Task task = project.task('generateAwsMixins')

    task.getOutputs().dir("${project.buildDir}/generated/com/netflix/awsobjectmapper")

    task.doLast({
      File outputDir = new File("${project.buildDir}/generated/com/netflix/awsobjectmapper")
      setupDir(outputDir)

      Pattern clientPattern = Pattern.compile('^([A-Za-z0-9]+)Client$');

      List<String> overrides = new ArrayList<String>()

      URL[] compileClasspath = project.configurations.getByName('compile').files.collect { it.toURI().toURL() }
      ClassLoader cl = new URLClassLoader(compileClasspath)

      new File(outputDir, "AmazonObjectMapperConfigurer.java").withWriter { out ->
        out.writeLine(mapperConfigurerHeader)
        String pkg = "com.amazonaws"
        ClassPath.from(cl).getTopLevelClassesRecursive(pkg).each { cinfo ->
          Matcher m = clientPattern.matcher(cinfo.simpleName)
          if (!cinfo.simpleName.endsWith("AsyncClient") && m.matches()) {
            String prefix = m.group(1);
            if (cinfo.name.contains("v2"))
              prefix = "V2$prefix"
            else if (cinfo.name.contains("2012_03_15"))
              prefix = "V2012_03_15$prefix"
            def clientClass = cinfo.load()
            def jarLoc = { Class clazz ->
              def path = clazz.protectionDomain?.codeSource?.location?.path ?: "unknown"
              path.substring(path.lastIndexOf('/') + 1)
            }
            println clientClass.name + ": " + jarLoc(clientClass)
            createMixins(out, outputDir, prefix, clientClass)
            overrides += collectOverrides(clientClass)
          }
        }
        out.writeLine("  }\n}\n")
      }
      new File(outputDir, "AmazonNamingStrategy.java").withWriter { out ->
        out.writeLine(strategyHeader)
        createNameForField(out, outputDir, overrides)
        createNameForGetterMethod(out, outputDir, overrides)
        createNameForSetterMethod(out, outputDir, overrides)
        out.writeLine("}\n")
      }
      new File(outputDir, "AmazonObjectMapper.java").withWriter { out ->
        out.writeLine(mapper);
      }
    })
  }

  def licenseHeader = """\
/*
 * Copyright 2014-2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
"""

  def mapperConfigurerHeader = """\
$licenseHeader
package com.netflix.awsobjectmapper;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class AmazonObjectMapperConfigurer {

  public static ObjectMapper createConfigured() {
    ObjectMapper objectMapper = new ObjectMapper();
    configure(objectMapper);
    return objectMapper;
  }

  public static void configure(ObjectMapper objectMapper) {
    objectMapper.configure(MapperFeature.AUTO_DETECT_IS_GETTERS, false);
    objectMapper.configure(SerializationFeature.INDENT_OUTPUT, true);
    objectMapper.configure(SerializationFeature.WRITE_NULL_MAP_VALUES, false);
    objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    objectMapper.setPropertyNamingStrategy(new AmazonNamingStrategy());
"""

  def mixinHeader = """\
$licenseHeader
package com.netflix.awsobjectmapper;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

"""

  def strategyHeader = """\
$licenseHeader
package com.netflix.awsobjectmapper;

import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.introspect.AnnotatedField;
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod;

class AmazonNamingStrategy extends PropertyNamingStrategy {
"""

  def mapper = """\
$licenseHeader

package com.netflix.awsobjectmapper;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * AmazonObjectMapper.
 *
 * @deprecated Use AmazonObjectMapperConfigurer and supply an ObjectMapper
 */
@Deprecated
public class AmazonObjectMapper extends ObjectMapper {
  public AmazonObjectMapper() {
    AmazonObjectMapperConfigurer.configure(this);
  }
}
"""
}
