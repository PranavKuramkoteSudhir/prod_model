import jenkins.model.*
import hudson.security.*
import hudson.util.*
import jenkins.install.*
import hudson.model.*
import jenkins.security.s2m.AdminWhitelistRule
import hudson.security.csrf.DefaultCrumbIssuer
import java.util.logging.Logger

def logger = Logger.getLogger("")
def instance = Jenkins.getInstance()
def pm = instance.getPluginManager()
def uc = instance.getUpdateCenter()
uc.updateAllSites()

def pluginsToInstall = [
    "git",                          // For Git integration
    "github",                       // For GitHub integration
    "workflow-aggregator",          // Pipeline plugin
    "docker-workflow",              // Docker Pipeline
    "docker-plugin",                // Docker plugin
    "credentials-binding",          // For handling credentials
    "workflow-basic-steps",         // For basic pipeline steps
    "ws-cleanup",                   // For workspace cleanup
    "pipeline-utility-steps"        // Additional pipeline utilities
]

def failedPlugins = []

// Install plugins
pluginsToInstall.each { pluginName ->
    logger.info("Checking " + pluginName)
    if (!pm.getPlugin(pluginName)) {
        logger.info("Looking up " + pluginName)
        def plugin = uc.getPlugin(pluginName)
        if (plugin) {
            logger.info("Installing " + pluginName)
            def installFuture = plugin.deploy()
            while(!installFuture.isDone()) {
                logger.info("Waiting for plugin " + pluginName + " to install...")
                sleep(3000)
            }
            logger.info("Plugin " + pluginName + " installed.")
        } else {
            failedPlugins.add(pluginName)
            logger.warning("Plugin " + pluginName + " not found!")
        }
    } else {
        logger.info("Plugin " + pluginName + " already installed.")
    }
}

// Skip setup wizard
instance.setInstallState(InstallState.INITIAL_SETUP_COMPLETED)

// Create admin user
def hudsonRealm = new HudsonPrivateSecurityRealm(false)
def adminUsername = "admin"
def adminPassword = "admin"

// Only create the admin user if it doesn't exist
if (hudsonRealm.getAllUsers().find { it.id == adminUsername } == null) {
    hudsonRealm.createAccount(adminUsername, adminPassword)
    logger.info("Admin user created")
}

instance.setSecurityRealm(hudsonRealm)

// Configure authorization
def strategy = new FullControlOnceLoggedInAuthorizationStrategy()
strategy.setAllowAnonymousRead(false)
instance.setAuthorizationStrategy(strategy)

// Enable CSRF protection
instance.setCrumbIssuer(new DefaultCrumbIssuer(true))

// Configure Global Settings
def desc = instance.getDescriptor("jenkins.model.JenkinsLocationConfiguration")
def url = "http://localhost:8080/"
desc.setUrl(url)

// Configure Docker environment variable
def globalNodeProperties = instance.getGlobalNodeProperties()
def envVarsNodePropertyList = globalNodeProperties.getAll(hudson.slaves.EnvironmentVariablesNodeProperty.class)

def envVars = null
if (envVarsNodePropertyList.size() == 0) {
    def newEnvVarsNodeProperty = new hudson.slaves.EnvironmentVariablesNodeProperty()
    globalNodeProperties.add(newEnvVarsNodeProperty)
    envVars = newEnvVarsNodeProperty.getEnvVars()
} else {
    envVars = envVarsNodePropertyList.get(0).getEnvVars()
}

// Add Docker environment variables
envVars.put("DOCKER_HOST", "unix:///var/run/docker.sock")

// Save configuration
instance.save()

// Log results
if (failedPlugins.size() > 0) {
    logger.warning("Failed plugins: " + failedPlugins)
} else {
    logger.info("All plugins installed successfully")
}

// Handle restart if required
if (uc.isRestartRequiredForCompletion()) {
    logger.info("Restart required")
    instance.save()
    System.setProperty("jenkins.install.runSetupWizard", "false")
    try {
        instance.safeRestart()
    } catch (Exception e) {
        logger.warning("Failed to restart Jenkins: " + e.getMessage())
    }
}