package com.cognifide.cq.includefilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.PropertyOption;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.engine.EngineConstants;
import org.osgi.framework.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cognifide.cq.includefilter.generator.IncludeGeneratorFactory;
import com.cognifide.cq.includefilter.processor.IncludeTagWritingProcessor;
import com.cognifide.cq.includefilter.processor.RequestPassingProcessor;
import com.cognifide.cq.includefilter.processor.SyntheticResourceIncludingProcessor;
import com.cognifide.cq.includefilter.processor.ResourceIncludingProcessor;
import com.cognifide.cq.includefilter.type.NoCache;
import com.cognifide.cq.includefilter.type.ResourceTypesProvider;

/**
 * Dynamic Include Filter (based on Sling Caching Filter)
 * 
 * @author tomasz.rekawek
 */

//@formatter:off
@Component(metatype = true, immediate = true, label = "Dynamic Include Filter", description = "Dynamic Include Filter")
@Service
@Properties({
	@Property(
		name = EngineConstants.SLING_FILTER_SCOPE,
		value = {EngineConstants.FILTER_SCOPE_REQUEST, EngineConstants.FILTER_SCOPE_INCLUDE },
		propertyPrivate = true),
	@Property(
		name = Constants.SERVICE_RANKING,
		intValue = 100,
		propertyPrivate = true),
	@Property(
		name = Configuration.PROPERTY_FILTER_ENABLED,
		boolValue = Configuration.DEFAULT_FILTER_ENABLED,
		label = "Enabled",
		description = "Check to enable the filter"),
	@Property(
		name = Configuration.PROPERTY_FILTER_RESOURCE_TYPES,
		value = { "foundation/components/carousel", "foundation/components/userinfo" },
		cardinality = Integer.MAX_VALUE,
		label = "Resource types",
		description = "Filter will replace components with selected resource types"),
	@Property(
		name = Configuration.PROPERTY_INCLUDE_TYPE,
		value = Configuration.DEFAULT_INCLUDE_TYPE,
		label = "Include type",
		description = "Type of generated include tags",
		options = {
				@PropertyOption(name = "SSI", value="Apache SSI"),
				@PropertyOption(name = "ESI", value="ESI"),
				@PropertyOption(name = "JSI", value="Javascript")}),
	@Property(
		name = Configuration.PROPERTY_ADD_COMMENT,
		boolValue = Configuration.DEFAULT_ADD_COMMENT,
		label = "Add comment",
		description = "Add comment to included components"),
	@Property(
		name = Configuration.PROPERTY_FILTER_SELECTOR,
		value = Configuration.DEFAULT_FILTER_SELECTOR,
		label = "Filter selector",
		description = "Selector used to mark included resources")
})
//@formatter:on
public class DynamicIncludeFilter implements Filter {
	private static final Logger LOG = LoggerFactory.getLogger(DynamicIncludeFilter.class);

	@Reference
	private IncludeGeneratorFactory generatorFactory;

	@Reference(referenceInterface = ResourceTypesProvider.class, cardinality = ReferenceCardinality.OPTIONAL_MULTIPLE, policy = ReferencePolicy.DYNAMIC)
	private Set<SupportedResourceTypes> resourceTypeProviders = new CopyOnWriteArraySet<SupportedResourceTypes>();

	private List<RequestProcessor> processors;

	private Configuration config;

	@Override
	public void init(FilterConfig filterConfig) {
	}

	@Override
	public void destroy() {
	}

	/**
	 * Handle OSGi activation
	 * 
	 * @param context osgi component context
	 */
	@Activate
	protected void activate(Map<String, Object> properties) {
		config = new Configuration(properties, resourceTypeProviders);
		if (config.isEnabled()) {
			LOG.debug("Filter is enabled");
		} else {
			LOG.debug("Filter is disabled");
		}
		processors = new ArrayList<RequestProcessor>();
		processors.add(new RequestPassingProcessor());
		processors.add(new SyntheticResourceIncludingProcessor(config));
		processors.add(new ResourceIncludingProcessor(config));
		processors.add(new IncludeTagWritingProcessor(config, generatorFactory));
	}

	/**
	 * Handle OSGi deactivation
	 * 
	 * @param context osgi component context
	 */
	@Deactivate
	protected void deactivate() {
		config = null;
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		if (!(config.isEnabled() && request instanceof SlingHttpServletRequest && response instanceof SlingHttpServletResponse)) {
			chain.doFilter(request, response);
			return;
		}
		SlingHttpServletRequest slingRequest = (SlingHttpServletRequest) request;
		SlingHttpServletResponse slingResponse = (SlingHttpServletResponse) response;
		for (RequestProcessor p : processors) {
			if (p.accepts(slingRequest)) {
				p.process(slingRequest, slingResponse, chain);
				return;
			}
		}
		chain.doFilter(request, response);
	}

	void bindResourceTypeProviders(ResourceTypesProvider provider) {
		LOG.info("bind new provider: " + provider.getClass().getName());
		resourceTypeProviders.add(new SupportedResourceTypes(provider));
		LOG.info("registered providers: " + resourceTypeProviders.size());
	}

	void unbindResourceTypeProviders(ResourceTypesProvider provider) {
		LOG.info("unbind provider: " + provider.getClass().getName());
		SupportedResourceTypes toRemove = null;
		for (SupportedResourceTypes type : resourceTypeProviders) {
			if (type.getProvider().equals(provider)) {
				toRemove = type;
				break;
			}
		}
		if (toRemove != null) {
			resourceTypeProviders.remove(toRemove);
		}
		LOG.info("registered providers: " + resourceTypeProviders.size());
	}

	static final class SupportedResourceTypes {
		private ResourceTypesProvider provider;

		private String[] cachedResourceTypes;

		private SupportedResourceTypes(ResourceTypesProvider provider) {
			this.provider = provider;
			if (!provider.getClass().isAnnotationPresent(NoCache.class)) {
				String[] providedTypes = provider.getResourceTypes();
				cachedResourceTypes = Arrays.copyOf(providedTypes, providedTypes.length);
			}
		}

		public String[] getResourceTypes() {
			if (cachedResourceTypes == null) {
				return provider.getResourceTypes();
			} else {
				return cachedResourceTypes;
			}
		}

		public ResourceTypesProvider getProvider() {
			return provider;
		}
	}
}
