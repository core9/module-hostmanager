package io.core9.hostmanager;

import io.core9.plugin.database.repository.AbstractCrudEntity;
import io.core9.plugin.database.repository.Collection;
import io.core9.plugin.server.VirtualHost;

import java.util.HashMap;
import java.util.Map;

@Collection("virtualhosts")
public class VirtualHostImpl extends AbstractCrudEntity implements VirtualHost {
	private String hostname;
	private Map<String,Object> context;
	
	public String getHostname() {
		return hostname;
	}

	public VirtualHost setHostname(String hostname) {
		this.hostname = hostname;
		return this;
	}
	
	public Map<String,Object> getContext() {
		return this.context;
	}
	
	public VirtualHost setContext(Map<String,Object> context) {
		this.context = context;
		return this;
	}
	
	@SuppressWarnings("unchecked")
	public <R> R putContext(String name, R value) {
		return (R) this.context.put(name, value);
	}
	
	@SuppressWarnings("unchecked")
	public <R> R getContext(String name) {
		return (R) this.context.get(name);
	}

	public <R> R getContext(String name, R defaultValue) {
        if (context.containsKey(name)) {
            return getContext(name);
        } else {
            return defaultValue;
        }
	}
	
	public VirtualHostImpl() {
		this.context = new HashMap<String, Object>();
	}

	public VirtualHostImpl(String hostname) {
		this.setHostname(hostname);
		this.context = new HashMap<String, Object>();
	}
	
	public VirtualHostImpl(VirtualHost vhost) {
		this.hostname = vhost.getHostname();
		this.context = vhost.getContext();
	}
}
