package io.core9.hostmanager;

import io.core9.core.boot.CoreBootStrategy;
import io.core9.plugin.database.mongodb.MongoDatabase;
import io.core9.plugin.database.repository.CrudRepository;
import io.core9.plugin.database.repository.NoCollectionNamePresentException;
import io.core9.plugin.database.repository.RepositoryFactory;
import io.core9.plugin.server.HostManager;
import io.core9.plugin.server.VirtualHost;
import io.core9.plugin.server.VirtualHostProcessor;
import io.core9.plugin.server.handler.Binding;
import io.core9.plugin.server.handler.Middleware;
import io.core9.plugin.server.request.Request;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

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
	public HostManager addVirtualHost(VirtualHost vhost) throws UnknownHostException {
		if(MASTERDB != null) {
			repository.create(MASTERDB, "", new VirtualHostImpl(vhost));
		}
		createVirtualHostDatabase(vhost);
		return this;
	}

	@Override
	public VirtualHost[] refreshVirtualHosts() {
		Map<String, VirtualHost> currentHosts = getVirtualHostsByHostname();
		try {
			List<VirtualHostImpl> vhosts = repository.getAll(MASTERDB, "");
			List<VirtualHost> newHosts = new ArrayList<VirtualHost>();
			for(VirtualHostImpl vhost : vhosts) {
				if(!currentHosts.containsKey(vhost.getHostname())) {
					vhost.putContext("bindings", new CopyOnWriteArrayList<Binding>());
					newHosts.add(vhost);
					currentHosts.put(vhost.getHostname(), vhost);
					createVirtualHostDatabase(vhost);
				}
			}
			setVirtualHostsOnPlugins(newHosts.toArray(new VirtualHost[newHosts.size()]));
			this.vhosts = currentHosts.values().toArray(new VirtualHost[currentHosts.values().size()]);
        } catch (Exception e) {
        	e.printStackTrace();
        }
		return this.vhosts;
	}
	
	@Override
	public VirtualHost[] getVirtualHosts() {
		if(this.vhosts == null) {
			this.vhosts = new VirtualHost[0];
		}
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
	public void processPlugins() {
		List<VirtualHostImpl> list = repository.getAll(MASTERDB, "");
		this.vhosts = new VirtualHost[list.size()];
		list.toArray(this.vhosts);
		for(VirtualHost vhost : this.vhosts) {
			try {
				createVirtualHostDatabase(vhost);
			} catch (UnknownHostException e) {
				e.printStackTrace();
			}
		}
		setVirtualHostsOnPlugins(this.vhosts);
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

	private void setVirtualHostsOnPlugins(VirtualHost[] vhosts) {
		for (Plugin plugin : this.registry.getPlugins()) {
			List<Class<?>> interfaces = ClassUtils.getAllInterfaces(plugin.getClass());
			if (interfaces.contains(VirtualHostProcessor.class)) {
				((VirtualHostProcessor) plugin).process(vhosts);
			}
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
						Map<String,Object> body = request.getBodyAsMap();
						try {
							addVirtualHost(parseContext(request.getVirtualHost(), body));
							request.getResponse().setTemplate("io.core9.admin.installed");
						} catch (UnknownHostException e) {
							request.getResponse().setStatusCode(500);
							request.getResponse().end("Error:" + e.getMessage());
						}
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
