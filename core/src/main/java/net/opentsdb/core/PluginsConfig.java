// This file is part of OpenTSDB.
// Copyright (C) 2017  The OpenTSDB Authors.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 2.1 of the License, or (at your
// option) any later version.  This program is distributed in the hope that it
// will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
// General Public License for more details.  You should have received a copy
// of the GNU Lesser General Public License along with this program.  If not,
// see <http://www.gnu.org/licenses/>.
package net.opentsdb.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;

import net.opentsdb.exceptions.PluginLoadException;
import net.opentsdb.query.pojo.Validatable;
import net.opentsdb.utils.Deferreds;
import net.opentsdb.utils.JSON;
import net.opentsdb.utils.PluginLoader;

/**
 * The configuration class that handles loading, initializing and shutting down
 * of TSDB plugins. It should ONLY be instantiated and dealt with via the 
 * {@link Registry}.
 * <p>
 * The class allows for plugin initialization ordering in that initialization
 * occurs by iterating over the {@link #configs} in order. Each initialization
 * will block (asynchronously) and only trigger the next plugin's init method
 * once it has completed. Shutdown happens in reverse initialization order by
 * default and can be changed via {@link #setShutdownReverse(boolean)}.
 * <p>
 * By default, if initialization fails for any plugin, an exception will be
 * returned. If you want to continue loading plugins and don't mind if one or
 * more fail to load, then set {@link #setContinueOnError(boolean)}.
 * <p>
 * There are two types of {@link PluginConfig} that can be handled by this class.
 * <ol>
 * <li>The first is an un-named <i>multi-instance</i> plugin wherein the plugin
 * directory and class path are scanned for all implementations of a plugin type.
 * Each type is loaded and identified by it's full class name. These are useful
 * for plugins like Filters and Factories where there may be many implementations
 * and the user may not reference them by name directly. Only one instance of
 * each concrete class can be loaded by the TSD. For these types, only the
 * {@link PluginConfig#setType(String)} field is populated and all other fields
 * are empty or false.</li>
 * <li>The second time is a specific concrete plugin where multiple instances
 * of the same plugin may be loaded with different configurations or uses. E.g.
 * Multiple executors of the same type may be loaded with different configs and
 * the user may reference them at query time by ID. For these plugins, the 
 * {@link PluginConfig#setPlugin(String)} must be populated (along with the
 * type) as well as either {@link PluginConfig#setId(String)} or 
 * {@link PluginConfig#setDefault(boolean)} must be true (not both at the same
 * time though). If default is true, then the ID in the plugin map will be
 * null and when {@link #getDefaultPlugin(Class)} is called, the plugin for
 * that type will be returned.</li>
 * </ol> 
 * <p>
 * <b>Validation:</b> The following rules pertain to validation:
 * <ul>
 * <li>{@link PluginConfig#setType(String)} cannot be empty or null.</li>
 * <li>If {@link PluginConfig#setPlugin(String)} is not empty or null then either
 * {@link PluginConfig#setId(String)} must be set to a non-empty value OR
 * {@link PluginConfig#setDefault(boolean)} must be set to true.</li>
 * <li>For each plugin type, each ID must be unique.</li>
 * <li>Only one default can exist for each plugin type.</li>
 * </ul>
 * 
 * @since 3.0
 */
@JsonInclude(Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PluginsConfig extends Validatable {
  private static final Logger LOG = LoggerFactory.getLogger(PluginsConfig.class);
  
  /** The list of plugin configs. */
  private List<PluginConfig> configs;
  
  /** A list of plugin locations. */
  private List<String> plugin_locations;
  
  /** Whether or not to continue initializing plugins on error. */
  private boolean continue_on_error;
  
  /** Whether or not to shutdown plugins in reverse initialization order. */
  private boolean shutdown_reverse = true;
  
  /** The list of configured and instantiated plugins. */
  private List<TsdbPlugin> instantiated_plugins;
  
  /** The map of plugins loaded by the TSD. This includes the 
   * {@link #instantiated_plugins} as well as those registered. */
  private final Map<Class<?>, Map<String, TsdbPlugin>> plugins;
  
  /** Default ctor */
  public PluginsConfig() {
    instantiated_plugins = Lists.newArrayList();
    plugins = Maps.newHashMapWithExpectedSize(1);
  }
  
  /** @param configs A list of plugin configurations. */
  public void setConfigs(final List<PluginConfig> configs) {
    this.configs = configs;
  }
  
  /** @param plugin_locations A list of plugins and/or directories. */
  public void setPluginLocations(final List<String> plugin_locations) {
    this.plugin_locations = plugin_locations;
  }
  
  /** @param continue_on_error Whether or not to continue initialization when
   * one or more plugins fail. */
  public void setContinueOnError(final boolean continue_on_error) {
    this.continue_on_error = continue_on_error;
  }
  
  /** @param shutdown_reverse Whether or not to shutdown plugins in reverse
   * initialization order. */
  public void setShutdownReverse(final boolean shutdown_reverse) {
    this.shutdown_reverse = shutdown_reverse;
  }
  
  /** @return An unmodifiable list of the configs. */
  public List<PluginConfig> getConfigs() {
    return configs == null ? Collections.emptyList() : 
      Collections.unmodifiableList(configs);
  }
  
  /** @return Whether or not to continue initialization when one or more 
   * plugins fail. */
  public boolean getContinueOnError() {
    return continue_on_error;
  }
  
  /** @return Whether or not to shutdown plugins in reverse initialization order. */
  public boolean getShutdownReverse() {
    return shutdown_reverse;
  }
  
  /** @return A list of plugins and/or locations. */
  public List<String> getPluginLocations() {
    return plugin_locations == null ? Collections.emptyList() : 
      Collections.unmodifiableList(plugin_locations);
  }
  
  /**
   * Registers the given plugin in the map. If a plugin with the ID is already
   * present, an exception is thrown.
   * <b>Warning:</b> The plugin MUST have been initialized prior to adding it
   * to the registry.
   * @param clazz The type of plugin to be stored.
   * @param id An ID for the plugin (may be null if it's a default).
   * @param plugin A non-null and initialized plugin to register.
   * @throws IllegalArgumentException if the class or plugin was null or if
   * a plugin was already registered with the given ID. Also thrown if the
   * plugin given is not an instance of the class.
   */
  public void registerPlugin(final Class<?> clazz, final String id, 
      final TsdbPlugin plugin) {
    if (clazz == null) {
      throw new IllegalArgumentException("Class cannot be null.");
    }
    if (plugin == null) {
      throw new IllegalArgumentException("Plugin cannot be null.");
    }
    if (!(clazz.isAssignableFrom(plugin.getClass()))) {
      throw new IllegalArgumentException("Plugin " + plugin 
          + " is not an instance of class " + clazz);
    }
    Map<String, TsdbPlugin> class_map = plugins.get(clazz);
    if (class_map == null) {
      class_map = Maps.newHashMapWithExpectedSize(1);
      plugins.put(clazz, class_map);
    } else {
      final TsdbPlugin extant = class_map.get(id);
      if (extant != null) {
        throw new IllegalArgumentException("Plugin with ID " + id 
            + " and class " + clazz + " already exists: " + extant);
      }
    }
    class_map.put(id, plugin);
  }
  
  /**
   * Retrieves the default plugin of the given type (i.e. the ID was null when
   * registered).
   * @param clazz The type of plugin to be fetched.
   * @return An instantiated plugin if found, null if not.
   * @throws IllegalArgumentException if the clazz was null.
   */
  public TsdbPlugin getDefaultPlugin(final Class<?> clazz) {
    return getPlugin(clazz, null);
  }
  
  /**
   * Retrieves the plugin with the given class type and ID.
   * @param clazz The type of plugin to be fetched.
   * @param id An optional ID, may be null if the default is fetched.
   * @return An instantiated plugin if found, null if not.
   * @throws IllegalArgumentException if the clazz was null.
   */
  public TsdbPlugin getPlugin(final Class<?> clazz, final String id) {
    if (clazz == null) {
      throw new IllegalArgumentException("Class cannot be null.");
    }
    final Map<String, TsdbPlugin> class_map = plugins.get(clazz);
    if (class_map == null) {
      return null;
    }
    return class_map.get(id);
  }
  
  /**
   * Initializes the plugins in the config in order of their appearance in the
   * list.
   * @param tsdb The TSDB used during initialization.
   * @return A deferred to wait on resolving to a null on success or an exception
   * if something went wrong.
   */
  public Deferred<Object> initialize(final TSDB tsdb) {
    // backwards compatibility.
    final String plugin_path = tsdb.getConfig()
        .getDirectoryName("tsd.core.plugin_path");
    if (plugin_locations == null && !Strings.isNullOrEmpty(plugin_path)) {
      plugin_locations = Lists.newArrayListWithCapacity(1);
      plugin_locations.add(plugin_path);
    } else if (!Strings.isNullOrEmpty(plugin_path)) {
      if (!plugin_locations.contains(plugin_path)) {
        plugin_locations.add(plugin_path);
      }
    }
    
    if (plugin_locations != null) {
      for (final String location : plugin_locations) {
        try {
          if (location.endsWith(".jar")) {
            PluginLoader.loadJAR(location);
            LOG.info("Loaded Plugin JAR: " + location);
          } else {
            PluginLoader.loadJARs(location);
            LOG.info("Loaded Plugin directory: " + location);
          }
        } catch (Exception e) {
          if (continue_on_error) {
            LOG.error("Unable to read from the plugin location: " + location 
                + " but configured to continue.", e);
          } else {
            return Deferred.fromError(new PluginLoadException(
                "Unable to read from plugin location: " + location, null, e));
          }
        }
      }
    }
    
    if (configs == null || configs.isEmpty()) {
      return Deferred.fromResult(null);
    }
    
    final List<PluginConfig> waiting_on_init = Lists.newArrayListWithCapacity(1);
    final Deferred<Object> deferred = new Deferred<Object>();
    
    /** The error handler for use when things go pear shaped. */
    class ErrorCB implements Callback<Object, Exception> {
      final int index;
      final Callback<Object, Object> downstream;
      
      ErrorCB(final int index, final Callback<Object, Object> downstream) {
        this.index = index;
        this.downstream = downstream;
      }
      
      @Override
      public Object call(final Exception ex) throws Exception {
        if (continue_on_error) {
          LOG.error("Unable to load plugin(s): " + waiting_on_init, ex);
          waiting_on_init.clear();
          downstream.call(null);
        } else {
          try {
            final PluginLoadException e = new PluginLoadException(
                "Initialization failed for plugin " 
                + configs.get(index).getPlugin(), 
                  configs.get(index).getPlugin(),
                  ex);
            deferred.callback(e);
          } catch (Exception e) {
            e.printStackTrace();
          }
        }
        return null;
      }
    }
    
    /** Loads and initializes the next plugin. */
    class SuccessCB implements Callback<Object, Object> {
      final int index;
      final SuccessCB downstream;
      
      SuccessCB(final int index, final SuccessCB downstream) {
        this.index = index;
        this.downstream = downstream;
      }
      
      @SuppressWarnings("unchecked")
      @Override
      public Object call(final Object ignored) throws Exception {
        for (final PluginConfig waiting : waiting_on_init) {
          registerPlugin(waiting);
        }
        waiting_on_init.clear();
        
        if (index >= configs.size() || index < 0) {
          if (LOG.isDebugEnabled() && index > 0) {
            LOG.debug("Completed loading.");
          }
          deferred.callback(null);
        } else {
          final PluginConfig plugin_config = configs.get(index);
          
          try {
            if (!(Strings.isNullOrEmpty(plugin_config.getId())) || 
                plugin_config.isDefault()) {
              
              // load specific
              final Class<?> type = Class.forName(plugin_config.getType());
              final TsdbPlugin plugin = (TsdbPlugin) PluginLoader
                  .loadSpecificPlugin(plugin_config.getPlugin(), type);
              if (plugin == null) {
                throw new RuntimeException("No plugin found for type: " 
                    + plugin_config.getType());
              }
              
              // stash the plugin while we're waiting for it to init.
              plugin_config.clazz = type;
              plugin_config.instantiated_plugin = plugin;
              waiting_on_init.add(plugin_config);
              
              LOG.info("Loaded plugin " + plugin.id() + " version: " 
                  + plugin.version());
              
              if (downstream != null) {
                plugin.initialize(tsdb)
                  .addCallback(downstream)
                  .addErrback(new ErrorCB(index, downstream));
              } else {
                plugin.initialize(tsdb)
                  .addErrback(new ErrorCB(-1, null));
              }
            } else {
              final Class<?> type = Class.forName(plugin_config.getType());
              final List<TsdbPlugin> plugins = 
                  (List<TsdbPlugin>) PluginLoader.loadPlugins(type);
              
              if (plugins == null || plugins.isEmpty()) {
                LOG.info("No plugins found for type: " + type);
                if (downstream == null) {
                  deferred.callback(null);
                } else {
                  downstream.call(null);
                }
              } else {
                final List<Deferred<Object>> deferreds = 
                    Lists.newArrayListWithCapacity(plugins.size());
                for (final TsdbPlugin plugin : plugins) {
                  deferreds.add(plugin.initialize(tsdb));
                  final PluginConfig waiting = new PluginConfig();
                  waiting.setPlugin(plugin_config.getPlugin());
                  waiting.clazz = type;
                  waiting.instantiated_plugin = plugin;
                  waiting_on_init.add(waiting);
                }
                
                if (downstream != null) {
                  Deferred.group(deferreds)
                    .addCallback(Deferreds.NULL_GROUP_CB)
                    .addCallback(downstream)
                    .addErrback(new ErrorCB(index, downstream));
                } else {
                  Deferred.group(deferreds)
                    .addCallback(Deferreds.NULL_GROUP_CB)
                    .addErrback(new ErrorCB(-1, null));
                }
              }
            }
          } catch (Exception e) {
            final PluginLoadException ex = new PluginLoadException(
                "Unable to find instances of plugin " 
                + plugin_config.getPlugin(), 
                  plugin_config.getPlugin(),
                  e);
            if (continue_on_error) {
              LOG.error("Unable to load plugin(s): " + configs.get(index), ex);
              downstream.call(null);
            } else {
              deferred.callback(ex);
            }
          }
        }
        return null;
      }
    }
    
    // build callback chain
    SuccessCB last_cb = new SuccessCB(configs.size(), null);
    for (int i = configs.size() - 1; i >= 0; i--) {
      if (last_cb == null) {
        last_cb = new SuccessCB(i, null);
      } else {
        final SuccessCB cb = new SuccessCB(i, last_cb);
        last_cb = cb;
      }
    }
    
    try {
      last_cb.call(null);
    } catch (Exception e) {
      LOG.error("Failed initial loading of plugins", e);
      deferred.callback(e);
    }
    
    return deferred;
  }
  
  /**
   * Shuts down all of the configured plugins as well as those that were 
   * registered after.
   * @return A deferred to wait on resolving to a null. Exceptions returned by
   * shutdown methods will be logged and not returned.
   */
  public Deferred<Object> shutdown() {
    if ((instantiated_plugins == null || instantiated_plugins.isEmpty()) && 
        plugins.isEmpty()) {
      return Deferred.fromResult(null);
    }
    
    final Deferred<Object> deferred = new Deferred<Object>();
    
    /** Error handler that continues shutdown with the next plugin. */
    class ErrorCB implements Callback<Object, Exception> {
      final TsdbPlugin plugin;
      final Callback<Object, Object> downstream;
      
      ErrorCB(final TsdbPlugin plugin, final Callback<Object, Object> downstream) {
        this.plugin = plugin;
        this.downstream = downstream;
      }
      
      @Override
      public Object call(final Exception ex) throws Exception {
        LOG.error("Failed shutting down plugin: " + plugin, ex);
        try {
          if (downstream != null) {
            downstream.call(null);
          } else {
            LOG.info("Completed shutdown of plugins.");
            deferred.callback(null);
          }
        } catch (Exception e) {
          LOG.error("Unexpected exception calling downstream", e);
        }
        return null;
      }
      
    }
    
    /** Shuts down the given plugin and continues downstream. */
    class SuccessCB implements Callback<Object, Object> {
      final TsdbPlugin plugin;
      final SuccessCB downstream;
      
      SuccessCB(final TsdbPlugin plugin, final SuccessCB downstream) {
        this.plugin = plugin;
        this.downstream = downstream;
      }

      @Override
      public Object call(Object arg) throws Exception {
        if (downstream == null) {
          LOG.info("Completed shutdown of plugins.");
          deferred.callback(null);
        } else {
          try {
            if (downstream != null) {
              plugin.shutdown()
                .addCallback(downstream)
                .addErrback(new ErrorCB(plugin, downstream));
            } else {
              plugin.shutdown()
                .addErrback(new ErrorCB(plugin, null));
            }
          } catch (Exception e) {
            LOG.error("Failed to shutdown plugin: " + plugin.id());
          }
        }
        return null;
      }
    }
    
    // build callback chain
    SuccessCB last_cb = new SuccessCB(null, null);
    if (shutdown_reverse) {
      LOG.info("Shutting down plugins in reverse order of initialization");
      for (int i = instantiated_plugins.size() - 1; i >= 0; i--) {
        if (last_cb == null) {
          last_cb = new SuccessCB(instantiated_plugins.get(i), null);
        } else {
          final SuccessCB cb = 
              new SuccessCB(instantiated_plugins.get(i), last_cb);
          last_cb = cb;
        }
      }
    } else {
      LOG.info("Shutting down plugins in same order as initialization");
      for (int i = 0; i < instantiated_plugins.size(); i++) {
        if (last_cb == null) {
          last_cb = new SuccessCB(instantiated_plugins.get(i), null);
        } else {
          final SuccessCB cb = 
              new SuccessCB(instantiated_plugins.get(i), last_cb);
          last_cb = cb;
        }
      }
    }
    
    // now add any other plugin that snuck in another way.
    for (final Map<String, TsdbPlugin> named_plugins : plugins.values()) {
      for (final TsdbPlugin plugin : named_plugins.values()) {
        if (instantiated_plugins.contains(plugin)) {
          continue;
        }
        if (last_cb == null) {
          last_cb = new SuccessCB(plugin, null);
        } else {
          final SuccessCB cb = new SuccessCB(plugin, last_cb);
          last_cb = cb;
        }
      }
    }
    
    try {
      last_cb.call(null);
    } catch (Exception e) {
      LOG.error("Failed shutdown of plugins", e);
      deferred.callback(e);
    }
    
    return deferred;
  }
  
  @Override
  public void validate() {
    if (configs == null || configs.isEmpty()) {
      // no worries mate.
      return;
    }
    
    final Map<String, Set<String>> type_id_map = Maps.newHashMap();
    final Set<String> defaults = Sets.newHashSet();
    final Set<String> multi_types = Sets.newHashSet();
    
    for (final PluginConfig config : configs) {
      // everyone MUST have a type.
      if (Strings.isNullOrEmpty(config.getType())) {
        throw new IllegalArgumentException("Type cannot be null or empty:" 
            + JSON.serializeToString(config));
      }
      
      // make sure it's either a multi-type or single type with the proper info.
      if (Strings.isNullOrEmpty(config.getId()) && !config.isDefault()) {
        if (multi_types.contains(config.getType())) {
          throw new IllegalArgumentException("Duplicate multi-type found. "
              + "Remove one of them:" + JSON.serializeToString(config));
        }
        multi_types.add(config.getType());
      } else {
        if (config.isDefault() && !Strings.isNullOrEmpty(config.getId())) {
          throw new IllegalArgumentException("Default configs cannot have "
              + "an ID: " + JSON.serializeToString(config));
        }
        if (!config.isDefault()) {
          if (Strings.isNullOrEmpty(config.getId())) {
            throw new IllegalArgumentException("Specific plugin instance must "
                + "have an ID if it is not the default: " 
                + JSON.serializeToString(config));
          }
          Set<String> ids = type_id_map.get(config.getType());
          if (ids != null && ids.contains(config.getId())) {
            throw new IllegalArgumentException("Duplicate ID found. "
                + "Remove or rename one: " + JSON.serializeToString(config));
          }
          if (ids == null) {
            ids = Sets.newHashSetWithExpectedSize(1);
            type_id_map.put(config.getType(), ids);
          }
          ids.add(config.getId());
        } else {
          if (defaults.contains(config.getType())) {
            throw new IllegalArgumentException("Cannot have more than one "
                + "default for a plugin type: " + JSON.serializeToString(config));
          }
          defaults.add(config.getType());
        }
      }
    }
  }
  
  /**
   * Helper method that determines how to register the plugin during initialization.
   * @param config A non-null plugin config.
   */
  void registerPlugin(final PluginConfig config) {
    if (Strings.isNullOrEmpty(config.getId()) && !config.isDefault()) {
      registerPlugin(config.clazz, 
          config.instantiated_plugin.getClass().getCanonicalName(), 
          config.instantiated_plugin);
    } else {
      registerPlugin(config.clazz, 
          config.isDefault() ? null : config.getId(), 
          config.instantiated_plugin);
    }
    instantiated_plugins.add(config.instantiated_plugin);
    LOG.info("Registered plugin " + config);
  }
  
  @VisibleForTesting
  List<TsdbPlugin> instantiatedPlugins() {
    return instantiated_plugins;
  }
  
  /**
   * A single plugin configuration.
   */
  @JsonInclude(Include.NON_DEFAULT)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class PluginConfig {
    /** The canonical class name of the plugin implementation. */
    private String plugin;
    
    /** A descriptive ID to associate with the plugin. */
    private String id;
    
    /** The canonical class name of the type of plugin implemented by the plugin. */
    private String type;

    /** Whether or not the plugin should be identified as the default for a type. */
    private boolean is_default;
    
    /** Used by the initialization routine for storing the class. */
    protected Class<?> clazz;
    
    /** Used by the initialization routine for storing the instantiated plugin. */
    protected TsdbPlugin instantiated_plugin;
    
    /** @return The canonical class name of the plugin implementation. */
    public String getPlugin() {
      return plugin;
    }
    
    /** @return A descriptive ID to associate with the plugin. */
    public String getId() {
      return id;
    }

    /** @return The canonical class name of the type of plugin implemented 
     * by the plugin. */
    public String getType() {
      return type;
    }
    
    /** @return Whether or not the plugin should be identified as the default 
     * for a type. */
    public boolean isDefault() {
      return is_default;
    }
    
    /** @param plugin The canonical class name of the plugin implementation. */
    public void setPlugin(final String plugin) {
      this.plugin = plugin;
    }
    
    /** @param id A descriptive ID to associate with the plugin. */
    public void setId(final String id) {
      this.id = id;
    }
  
    /** @param type The canonical class name of the type of plugin implemented 
     * by the plugin.*/
    public void setType(final String type) {
      this.type = type;
    }
    
    /** @param is_default Whether or not the plugin should be identified as 
     * the default for a type. */
    public void setDefault(final boolean is_default) {
      this.is_default = is_default;
    }
  
    @Override
    public String toString() {
      return new StringBuilder()
          .append("id=")
          .append(id)
          .append(", type=")
          .append(type)
          .append(", plugin=")
          .append(plugin)
          .append(", isDefault=")
          .append(is_default)
          .toString();
    }
  }
}
