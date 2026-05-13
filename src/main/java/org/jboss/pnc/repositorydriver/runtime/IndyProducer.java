package org.jboss.pnc.repositorydriver.runtime;

//import org.jboss.pnc.repositorydriver.indy.IndyPNCOAuthBearerAuthenticator;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 * @ApplicationScoped
 *                    public class IndyProducer {
 * 
 *                    private static final Logger logger = LoggerFactory.getLogger(IndyProducer.class);
 * 
 * @Inject
 *         Configuration configuration;
 * 
 * @Inject
 *         IndyPNCOAuthBearerAuthenticator indyPNCOAuthBearerAuthenticator;
 * 
 *         protected SiteConfig indySiteConfig;
 *         protected IndyClientModule[] indyModules;
 *         protected Indy indy;
 * 
 * @Inject
 *         ManagedExecutor executor;
 * 
 *         private HttpClient httpClient;
 * 
 * @PostConstruct
 *                void init() throws NoSuchAlgorithmException {
 *                httpClient = java.net.http.HttpClient.newBuilder()
 *                .sslContext(SSLContext.getDefault())
 *                .executor(executor)
 *                .connectTimeout(Duration.ofSeconds(configuration.getHttpClientConnectTimeout()))
 *                .build();
 * 
 *                String baseUrl = StringUtils.stripEnd(configuration.getIndyUrl(), "/");
 *                if (!baseUrl.endsWith("/api")) {
 *                baseUrl += "/api";
 *                }
 * 
 *                Boolean indyClientMetricsEnabled = configuration.getIndyClientMetricsEnabled();
 *                SiteConfigBuilder indySiteConfigBuilder = new SiteConfigBuilder("indy", baseUrl)
 *                .withRequestTimeoutSeconds(configuration.getIndyClientRequestTimeout())
 *                .withMaxConnections(10)
 *                .withMetricEnabled(indyClientMetricsEnabled);
 *                if (indyClientMetricsEnabled) {
 *                Optional<String> honeycombDataset = configuration.getIndyClientMetricsHoneycombDataset();
 *                Optional<String> honeycombWriteKey = configuration.getIndyClientMetricsHoneycombWriteKey();
 *                Optional<Integer> baseSampleRate = configuration.getIndyClientMetricsBaseSampleRate();
 *                if (honeycombDataset.isPresent()) {
 *                indySiteConfigBuilder.withHoneycombDataset(honeycombDataset.get());
 *                }
 *                if (honeycombWriteKey.isPresent()) {
 *                indySiteConfigBuilder.withHoneycombWriteKey(honeycombWriteKey.get());
 *                }
 *                if (baseSampleRate.isPresent()) {
 *                indySiteConfigBuilder.withBaseSampleRate(baseSampleRate.get());
 *                }
 *                }
 *                indySiteConfig = indySiteConfigBuilder.build();
 * 
 *                indyModules = new IndyClientModule[] {
 *                new IndyFoloAdminClientModule(),
 *                new IndyFoloContentClientModule(),
 *                new IndyPromoteClientModule() };
 *                }
 * 
 * @Produces
 *           synchronized Indy createIndyServiceAccountClient() {
 *           try {
 *           indy = new Indy(
 *           indySiteConfig,
 *           indyPNCOAuthBearerAuthenticator,
 *           new IndyObjectMapper(true),
 *           MdcUtils.mdcToMapWithHeaderKeys(),
 *           indyModules);
 *           return indy;
 *           } catch (RepositoryDriverException | IndyClientException e) {
 *           logger.error("Failed to create Indy client: " + e.getMessage(), e);
 *           return null;
 *           }
 *           }
 * 
 * @Produces
 *           public HttpClient getHttpClient() {
 *           return httpClient;
 *           }
 * 
 * @Produces
 *           public IndyContentClientModule getIndyContentClientModule() {
 *           return new IndyContentClientModule();
 *           }
 * 
 *           // >>> Indy client required beans - start
 * 
 *           // @Produces
 *           // public GoldenSignalsMetricSet clientMetricSet() {
 *           // return new ClientGoldenSignalsMetricSet();
 *           // }
 *           //
 *           // @Produces
 *           // public MetricsConfig metricsConfig() {
 *           // return new DefaultMetricsConfig();
 *           // }
 *           //
 *           // @Produces
 *           // public StoragePathProvider storagePathProvider() {
 *           // return () -> null;
 *           // }
 *           //
 *           // @Produces
 *           // public TrafficClassifier trafficClassifier() {
 *           // return new ClientTrafficClassifier();
 *           // }
 *           //
 *           // @Produces
 *           // public WeftConfig weftConfig() {
 *           // return new DefaultWeftConfig();
 *           // }
 * 
 *           // <<< Indy client required beans - end
 * 
 * @PreDestroy
 *             void destroy() {
 *             if (indy != null) {
 *             indy.close();
 *             }
 *             }
 *             }
 */
