package io.core9.hostmanager;

import io.core9.core.boot.CoreBootStrategy;
import io.core9.plugin.database.mongodb.MongoDatabase;
import io.core9.plugin.database.repository.CrudRepository;
import io.core9.plugin.database.repository.NoCollectionNamePresentException;
import io.core9.plugin.database.repository.RepositoryFactory;
import io.core9.plugin.server.HostManager;
import io.core9.plugin.server.VirtualHost;
import io.core9.plugin.server.VirtualHostProcessor;
import io.core9.plugin.server.handler.Middleware;
import io.core9.plugin.server.request.Request;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.xeoh.plugins.base.Plugin;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import net.xeoh.plugins.base.annotations.events.PluginLoaded;

import org.apache.commons.lang3.ClassUtils;

@PluginImplementation
public class HostManagerImpl extends CoreBootStrategy implements HostManager {
	
	private static final String CONFIGURATION_COLLECTION = "configuration";
	
	private static String MASTERDB;
	
	private VirtualHost[] vhosts;
	private CrudRepository<VirtualHostImpl> repository;
	private MongoDatabase database;
		
	@PluginLoaded
	public void onDatabaseLoaded(MongoDatabase database) {
		MASTERDB = database.getMasterDBName();
		this.database = database;
	}
	
	@PluginLoaded
	public void onRepositoryFactoryLoaded(RepositoryFactory factory) throws NoCollectionNamePresentException {
		repository = factory.getCachedRepository(VirtualHostImpl.class);
	}
	
	@Override
	public VirtualHost[] getVirtualHosts() {
		return this.vhosts;
	}
	
	@Override
	public Map<String, VirtualHost> getVirtualHostsByHostname() {
		Map<String, VirtualHost> hosts = new HashMap<String, VirtualHost>();
		for(VirtualHost vhost : getVirtualHosts()) {
			hosts.put(vhost.getHostname(), vhost);
		}
		return hosts;
	}
	
	@Override
	public HostManager addVirtualHost(VirtualHost vhost) {
		if(MASTERDB != null) {
			repository.create(MASTERDB, "", new VirtualHostImpl(vhost));
			try {
				createVirtualHostDatabase(vhost);
			} catch (UnknownHostException e) {
				e.printStackTrace();
			}
		}
		int size = vhosts.length;
		vhosts = Arrays.copyOf(vhosts, size++);
		vhosts[size] = vhost;
		for (Plugin plugin : this.registry.getPlugins()) {
			List<Class<?>> interfaces = ClassUtils.getAllInterfaces(plugin.getClass());
			if (interfaces.contains(VirtualHostProcessor.class)) {
				((VirtualHostProcessor) plugin).addVirtualHost(vhost);
			}
		}
		return this;
	}
	
	@Override
	public HostManager removeVirtualHost(VirtualHost vhost) {
		List<VirtualHost> list = Arrays.asList(vhosts);
		list.remove(vhost);
		vhosts = list.toArray(new VirtualHost[list.size()]);
		if(MASTERDB != null) {
			repository.delete(MASTERDB, "", (VirtualHostImpl) vhost);
		}
		for (Plugin plugin : this.registry.getPlugins()) {
			List<Class<?>> interfaces = ClassUtils.getAllInterfaces(plugin.getClass());
			if (interfaces.contains(VirtualHostProcessor.class)) {
				((VirtualHostProcessor) plugin).removeVirtualHost(vhost);
			}
		}
		return this;
	}
	
	@Override
	public HostManager refreshVirtualHosts() {
		// Keep track of current state and new state
		List<VirtualHost> newVirtualHosts = new ArrayList<VirtualHost>();
		Map<String, VirtualHost> oldVirtualHosts = this.getVirtualHostsByHostname();
		List<VirtualHostImpl> dbVirtualHosts = repository.getAll(MASTERDB, "");
		VirtualHost[] vhosts = new VirtualHost[dbVirtualHosts.size()];
		
		// Check if vhosts are new (to be added) or old (to be removed)
		for(int i = vhosts.length; --i >= 0; ) {
			VirtualHost vhost = dbVirtualHosts.get(i);
			if(oldVirtualHosts.containsKey(vhost.getHostname())) {
				vhost = oldVirtualHosts.remove(vhost.getHostname());
			} else {
				newVirtualHosts.add(vhost);
			}
			vhosts[i] = vhost;
		}
		// Remove or add all vhosts
		for (Plugin plugin : this.registry.getPlugins()) {
			List<Class<?>> interfaces = ClassUtils.getAllInterfaces(plugin.getClass());
			if (interfaces.contains(VirtualHostProcessor.class)) {
				newVirtualHosts.forEach(vhost -> {
					((VirtualHostProcessor) plugin).addVirtualHost(vhost);
				});
				oldVirtualHosts.values().forEach(vhost -> {
					((VirtualHostProcessor) plugin).removeVirtualHost(vhost);
				});
			}
		}	
		this.vhosts = vhosts;
		return this;
	}

	@Override
	public void processPlugins() {
		List<VirtualHostProcessor> processors = new ArrayList<VirtualHostProcessor>();
		for (Plugin plugin : this.registry.getPlugins()) {
			if (ClassUtils.getAllInterfaces(plugin.getClass()).contains(VirtualHostProcessor.class)) {
				processors.add((VirtualHostProcessor) plugin);
			}
		}
		List<VirtualHostImpl> list = repository.getAll(MASTERDB, "");
		list.forEach(vhost -> {
			try {
				createVirtualHostDatabase(vhost);
				processors.forEach(processor -> {
					processor.addVirtualHost(vhost);
				});
			} catch (UnknownHostException e) {
				e.printStackTrace();
			}
		});
		this.vhosts = list.toArray(new VirtualHost[list.size()]);
	}
	
	/**
	 * Create database references for vitualhosts
	 * @param vhost
	 */
	private void createVirtualHostDatabase(VirtualHost vhost) throws UnknownHostException {
		if(vhost.getContext("dbhost") != null && !vhost.getContext("dbhost").equals("")) {
			database.addDatabase((String) vhost.getContext("dbhost"), (String) vhost.getContext("database"), (String) vhost.getContext("dbuser"), (String) vhost.getContext("password"));
		} else {
			database.addDatabase((String) vhost.getContext("database"), (String) vhost.getContext("dbuser"), (String) vhost.getContext("password"));
		}
	}
	
	@Override
	public Integer getPriority() {
		return 320;
	}

	@Override
	public Middleware getInstallationProcedure() {
		return new Middleware() {
			@Override
			public void handle(Request request) {
				if(request.getVirtualHost().getContext("newhost", false)) {
					switch(request.getMethod()) {
					case POST:
						Map<String,Object> body = request.getBodyAsMap().toBlocking().last();
						addVirtualHost(parseContext(request.getVirtualHost(), body));
						request.getResponse().setTemplate("io.core9.admin.installed");
						break;
					case GET:
					default:
						request.getResponse().addValue("hostname", request.getHostname());
						request.getResponse().setTemplate("io.core9.admin.install");
						break;
					}
				} else {
					request.getResponse().setTemplate("io.core9.admin.alreadyinstalled");
				}
			}
		};
	}

	/**
	 * Setup a new virtualhost
	 * @param vhost
	 * @param body
	 * @throws UnknownHostException 
	 */
	private VirtualHost parseContext(VirtualHost vhost, Map<String, Object> body) {
		Map<String,Object> context = new HashMap<String,Object>();
		context.put("prefix", body.get("prefix"));
		context.put("database", (String) body.get("database"));
		context.put("dbuser", (String) body.get("username"));
		context.put("password", (String) body.get("password"));
		context.put("dbhost", (String) body.get("dbhost"));
		vhost.setContext(context);
		return vhost;
	}

	@SuppressWarnings("unchecked")
	@Override
	public String getURLAlias(VirtualHost vhost, String path) {
		Map<String,Object> doc = new HashMap<String, Object>();
		doc.put("from", path);
		Map<String,Object> elemMatch = new HashMap<String, Object>();
		elemMatch.put("$elemMatch", doc);
		Map<String,Object> fields = new HashMap<String, Object>();
		fields.put("aliases", elemMatch);
		
		Map<String,Object> query = new HashMap<String, Object>();
		query.put("aliases.from", path);
		query.put("name", "aliases");
		
		Map<String,Object> result = database.getSingleResult((String) vhost.getContext("database"), vhost.getContext("prefix") + CONFIGURATION_COLLECTION, query, fields);
		if(result != null && result.get("aliases") != null) {
			return (String) ((List<Map<String,Object>>) result.get("aliases")).get(0).get("to");
		}
		return null;
	}
}
