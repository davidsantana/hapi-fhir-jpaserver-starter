package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.CacheControlDirective;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.client.interceptor.LoggingInterceptor;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r5.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.net.URI;

import static ca.uhn.fhir.util.TestUtil.waitForSize;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = {Application.class}, properties =
  {
     "spring.datasource.url=jdbc:h2:mem:dbr5",
     "hapi.fhir.fhir_version=r5",
	  "hapi.fhir.cr_enabled=false",
	  "hapi.fhir.subscription.websocket_enabled=true",
	  "hapi.fhir.remote_terminology_service.snomed.system=http://snomed.info/sct",
	  "hapi.fhir.remote_terminology_service.snomed.url=https://tx.fhir.org/r5"
  })
public class ExampleServerR5IT {

  private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(ExampleServerDstu2IT.class);
  private IGenericClient ourClient;
  private FhirContext ourCtx;

  public static final String SUBSCRIPTION_TOPIC_TEST_URL = "http://example.com/topic/test";


  @LocalServerPort
  private int port;


  @Test
  void testCreateAndRead() {

    String methodName = "testCreateResourceConditional";

    Patient pt = new Patient();
    pt.addName().setFamily(methodName);
    IIdType id = ourClient.create().resource(pt).execute().getId();

    Patient pt2 = ourClient.read().resource(Patient.class).withId(id).execute();
    assertEquals(methodName, pt2.getName().get(0).getFamily());
  }

  @Test
  void testWebsocketSubscription() throws Exception {
	  String endpoint = "ws://localhost:" + port + "/websocket";
    /*
     * Create topic
     */
    SubscriptionTopic topic = new SubscriptionTopic();

	 topic.setUrl(SUBSCRIPTION_TOPIC_TEST_URL);
	  topic.setStatus(Enumerations.PublicationStatus.ACTIVE);
	  SubscriptionTopic.SubscriptionTopicResourceTriggerComponent trigger = topic.addResourceTrigger();
	  trigger.setResource("Observation");
	  trigger.addSupportedInteraction(SubscriptionTopic.InteractionTrigger.CREATE);
	  trigger.addSupportedInteraction(SubscriptionTopic.InteractionTrigger.UPDATE);

	 ourClient.create().resource(topic).execute();

	  waitForSize(1, () -> ourClient
		  .search()
		  .forResource(SubscriptionTopic.class)
		  .where(Subscription.STATUS.exactly().code("active"))
		  .cacheControl(
			  new CacheControlDirective()
				  .setNoCache(true))
		  .returnBundle(Bundle.class)
		  .execute()
		  .getEntry()
		  .size());

	  /*
     * Create subscription
     */
    Subscription subscription = new Subscription();

    subscription.setTopic(SUBSCRIPTION_TOPIC_TEST_URL);
    subscription.setReason("Monitor new neonatal function (note, age will be determined by the monitor)");
    subscription.setStatus(Enumerations.SubscriptionStatusCodes.REQUESTED);
    subscription.getChannelType()
      .setSystem("http://terminology.hl7.org/CodeSystem/subscription-channel-type")
      .setCode("websocket");
    subscription.setContentType("application/fhir+json");
	 subscription.setEndpoint(endpoint);

    MethodOutcome methodOutcome = ourClient.create().resource(subscription).execute();
    IIdType mySubscriptionId = methodOutcome.getId();

    // Wait for the subscription to be activated
    waitForSize(1, () -> ourClient
		 .search()
		 .forResource(Subscription.class)
		 .where(Subscription.STATUS.exactly().code("active"))
		 .cacheControl(
			 new CacheControlDirective()
				 .setNoCache(true))
		 .returnBundle(Bundle.class)
		 .execute()
		 .getEntry()
		 .size());

    /*
     * Attach websocket
     */

	  SocketImplementation mySocketImplementation = new SocketImplementation(mySubscriptionId.getIdPart(),
		  EncodingEnum.JSON);

	  URI echoUri = new URI(endpoint);

	  WebSocketContainer container = ContainerProvider.getWebSocketContainer();

	  ourLog.info("Connecting to : {}", echoUri);
	  Session session = container.connectToServer(mySocketImplementation, echoUri);
	  ourLog.info("Connected to WS: {}", session.isOpen());

    /*
     * Create a matching resource
     */
    Observation obs = new Observation();
    obs.setStatus(Enumerations.ObservationStatus.FINAL);
    ourClient.create().resource(obs).execute();

    /*
     * Ensure that we receive a ping on the websocket
     */
    await().until(() -> mySocketImplementation.myPingCount > 0);

    /*
     * Clean up
     */
    ourClient.delete().resourceById(mySubscriptionId).execute();
  }


	@Test
	void testValidateRemoteTerminology() {

		String testCodeSystem = "http://foo/cs";
		String testValueSet = "http://foo/vs";
		ourClient.create().resource(new CodeSystem().setUrl(testCodeSystem).addConcept(new CodeSystem.ConceptDefinitionComponent().setCode("yes")).addConcept(new CodeSystem.ConceptDefinitionComponent().setCode("no"))).execute();
		ourClient.create().resource(new ValueSet().setUrl(testValueSet).setCompose(new ValueSet.ValueSetComposeComponent().addInclude(new ValueSet.ConceptSetComponent().setSystem(testValueSet)))).execute();

		Parameters remoteResult = ourClient.operation().onType(ValueSet.class).named("$validate-code").withParameter(Parameters.class, "code", new StringType("22298006")).andParameter("system", new UriType("http://snomed.info/sct")).execute();
		assertEquals(true, ((BooleanType) remoteResult.getParameterValue("result")).getValue());
		assertEquals("Myocardial infarction", ((StringType) remoteResult.getParameterValue("display")).getValue());

		Parameters localResult = ourClient.operation().onType(CodeSystem.class).named("$validate-code").withParameter(Parameters.class, "url", new UrlType(testCodeSystem)).andParameter("coding", new Coding(testCodeSystem, "yes", null)).execute();
	}

  @BeforeEach
  void beforeEach() {

    ourCtx = FhirContext.forR5();
    ourCtx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
    ourCtx.getRestfulClientFactory().setSocketTimeout(1200 * 1000);
    String ourServerBase = "http://localhost:" + port + "/fhir/";
    ourClient = ourCtx.newRestfulGenericClient(ourServerBase);
    ourClient.registerInterceptor(new LoggingInterceptor(true));
  }
}
