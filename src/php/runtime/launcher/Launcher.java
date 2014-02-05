package php.runtime.launcher;

import php.runtime.Information;
import php.runtime.Memory;
import php.runtime.common.StringUtils;
import php.runtime.env.CompileScope;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.ext.support.Extension;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.StringMemory;
import php.runtime.reflection.ModuleEntity;
import ru.regenix.jphp.compiler.jvm.JvmCompiler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Properties;

public class Launcher {
    protected final String[] args;
    protected CompileScope compileScope;
    protected Environment environment;
    protected Properties config;
    protected String pathToConf;

    protected OutputStream out;

    public Launcher(String pathToConf, String[] args) {
        this.args = args;
        this.out = System.out != null ? System.out : new ByteArrayOutputStream();
        this.compileScope = new CompileScope();
        this.pathToConf = pathToConf == null ? "JPHP-INF/launcher.conf" : pathToConf;
    }

    public Launcher(String[] args) {
        this(null, args);
    }

    public Launcher() {
        this(new String[0]);
    }

    protected InputStream getResource(String name){
        return Launcher.class.getClassLoader().getResourceAsStream(name);
    }

    protected Context getContext(String file){
        InputStream bootstrap = getResource(file);
        if (bootstrap != null) {
            return new Context(bootstrap, file, environment.getDefaultCharset());
        } else
            return null;
    }

    protected void initModule(ModuleEntity moduleEntity){
        compileScope.loadModule(moduleEntity);
        compileScope.addUserModule(moduleEntity);
        environment.registerModule(moduleEntity);
    }

    protected ModuleEntity loadFromCompiled(String file){
        InputStream inputStream = getResource(file);
        if (inputStream == null)
            return null;
        Context context = new Context(inputStream, file, environment.getDefaultCharset());

        ModuleDumper moduleDumper = new ModuleDumper(context, environment, true);
        try {
            return moduleDumper.load(inputStream);
        } catch (IOException e) {
            environment.catchUncaught(e);
            return null;
        }
    }

    protected ModuleEntity loadFromFile(String file){
        InputStream inputStream = getResource(file);
        if (inputStream == null)
            return null;
        Context context = new Context(inputStream, file, environment.getDefaultCharset());

        try {
            JvmCompiler compiler = new JvmCompiler(environment, context);
            return compiler.compile(false);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected ModuleEntity loadFrom(String file){
        if (file.endsWith(".phb"))
            return loadFromCompiled(file);
        else
            return loadFromFile(file);
    }

    protected void readConfig(){
        this.config = new Properties();
        this.compileScope.configuration = new HashMap<String, Memory>();

        InputStream resource;

        resource = getResource(pathToConf);
        if (resource != null) {
            try {
                this.config.load(resource);

                for (String name : config.stringPropertyNames()){
                    compileScope.configuration.put(name, new StringMemory(config.getProperty(name)));
                }
            } catch (IOException e) {
                throw new LaunchException(e.getMessage());
            }
        } else {
            throw new LaunchException("Cannot find configuration: " + pathToConf);
        }
    }

    protected void initExtensions(){
        String tmp = this.config.getProperty("extensions", "bcmath, ctype, calendar, date, spl");
        String[] extensions = StringUtils.split(tmp, ",");

        for(String ext : extensions){
            String className = Information.EXTENSIONS.get(ext.trim().toLowerCase());
            if (className == null)
                className = ext.trim();

            try {
                Extension extension = (Extension) Class.forName(className).newInstance();
                compileScope.registerExtension(extension);
            } catch (Exception e) {
                throw new LaunchException("Extension load error: " + e.getClass() + " - " + e.getMessage());
            }
        }

        this.environment = new Environment(compileScope, out);
        environment.getDefaultBuffer().setImplicitFlush(true);
    }

    public void run() throws Throwable {
        readConfig();
        initExtensions();

        String file = config.getProperty("bootstrap.file", null);
        if (file != null){
            ModuleEntity bootstrap = loadFrom(file);
            if (bootstrap == null)
                throw new LaunchException("Cannot find '" + file + "' resource");

            initModule(bootstrap);
            try {
                bootstrap.include(environment);
            } catch (Exception e){
                environment.catchUncaught(e);
            }
        }
    }

    public OutputStream getOut(){
        return out;
    }

    public CompileScope getCompileScope() {
        return compileScope;
    }

    public static void main(String[] args) throws Throwable {
        Launcher launcher = new Launcher(args);
        launcher.run();
    }
}