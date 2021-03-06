package org.eclipse.microprofile.jwt.test.jaxrs;


import io.undertow.servlet.ServletExtension;
import org.eclipse.microprofile.jwt.Claims;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.jwt.impl.DefaultJWTCallerPrincipalFactory;
import org.eclipse.microprofile.jwt.principal.JWTCallerPrincipal;
import org.eclipse.microprofile.jwt.principal.JWTCallerPrincipalFactory;
import org.eclipse.microprofile.jwt.tck.util.TokenUtils;
import org.eclipse.microprofile.jwt.wfswarm.JWTAuthMethodExtension;
import org.eclipse.microprofile.jwt.wfswarm.cdi.MPJWTExtension;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.Filters;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.resolver.api.maven.ConfigurableMavenResolverSystem;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;
import org.jboss.weld.probe.ProbeExtension;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.enterprise.inject.spi.Extension;
import javax.security.enterprise.CallerPrincipal;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;

import static javax.ws.rs.core.MediaType.TEXT_PLAIN;

/**
 *
 */
@RunWith(Arquillian.class)
public class RolesAllowedTest {

    // The RolesEndpoint.json JWT
    private static String token;
    // Time claims in the token
    private static Long iatClaim;
    private static Long authTimeClaim;
    private static Long expClaim;
    @ArquillianResource
    private URL baseURL;

    @Deployment(testable = false)
    public static WebArchive createDeployment() throws IOException {
        //System.setProperty("swarm.resolver.offline", "true");
        //System.setProperty("swarm.debug.port", "8888");
        //System.setProperty("org.jboss.weld.development", "true");
        //System.setProperty("org.jboss.weld.probe.exportDataAfterDeployment", "/tmp/cdi.out");

        //System.setProperty("swarm.logging", "TRACE");
        ConfigurableMavenResolverSystem resolver = Maven.configureResolver().workOffline();
        File wfswarmauth = resolver.resolve("org.eclipse.microprofile.jwt:jwt-auth-wfswarm:1.0-SNAPSHOT").withoutTransitivity().asSingleFile();
        File[] resteasy = resolver.resolve("org.jboss.resteasy:resteasy-json-p-provider:3.0.6.Final").withTransitivity().asFile();
        File[] ri = resolver.resolve("org.eclipse.microprofile.jwt:jwt-auth-principal-prototype:1.0-SNAPSHOT").withTransitivity().asFile();
        URL publicKey = RolesAllowedTest.class.getResource("/publicKey.pem");
        WebArchive webArchive = ShrinkWrap
                .create(WebArchive.class, "RolesAllowedTest.war")
                .addAsLibraries(wfswarmauth)
                .addAsLibraries(ri)
                .addAsLibraries(resteasy)
                .addAsResource(publicKey, "/publicKey.pem")
                .addAsManifestResource(publicKey, "/MP-JWT-SIGNER")
                .addAsResource("project-defaults.yml", "/project-defaults.yml")
                //.addAsResource("project-defaults-basic.yml", "/project-defaults.yml")
                .addPackage(JWTCallerPrincipal.class.getPackage())
                .addClass(RolesEndpoint.class)
                .addClass(IService.class)
                .addClass(ServiceEJB.class)
                .addClass(ServiceServlet.class)
                .addClass(JsonWebToken.class)
                .addClass(CallerPrincipal.class)

                .addAsServiceProvider(JWTCallerPrincipalFactory.class, DefaultJWTCallerPrincipalFactory.class)
                .addAsServiceProvider(ServletExtension.class, JWTAuthMethodExtension.class)
                .addAsServiceProvider(Extension.class, MPJWTExtension.class)
                //.addAsServiceProvider(Extension.class, ProbeExtension.class)
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml")
                .addAsWebInfResource("jwt-roles.properties", "classes/jwt-roles.properties")
                .addAsWebInfResource("WEB-INF/web.xml", "web.xml")
                .addAsWebInfResource("WEB-INF/jboss-web.xml", "jboss-web.xml")
                ;
        System.out.printf("WebArchive: %s\n", webArchive.toString(true));
        return webArchive;
    }

    @BeforeClass
    public static void generateToken() throws Exception {
        HashMap<String, Long> timeClaims = new HashMap<>();
        token = TokenUtils.generateTokenString("/RolesEndpoint.json", null, timeClaims);
        iatClaim = timeClaims.get(Claims.iat.name());
        authTimeClaim = timeClaims.get(Claims.auth_time.name());
        expClaim = timeClaims.get(Claims.exp.name());
    }
    @Test
    public void callEchoNoAuth() throws Exception {
        String uri = baseURL.toExternalForm() + "/endp/echo";
        WebTarget echoEndpointTarget = ClientBuilder.newClient()
                .target(uri)
                .queryParam("input", "hello")
                ;
        Response response = echoEndpointTarget.request(TEXT_PLAIN).get();
        Assert.assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, response.getStatus());
    }

    /**
     * Used to test how a standard auth-method works with the authorization layer.
     * @throws Exception
     */
    @Test
    @Ignore
    public void callEchoBASIC() throws Exception {
        byte[] tokenb = Base64.getEncoder().encode("jdoe@example.com:password".getBytes());
        String token = new String(tokenb);
        System.out.printf("basic: %s\n", token);

        String uri = baseURL.toExternalForm() + "/endp/echo";
        WebTarget echoEndpointTarget = ClientBuilder.newClient()
                .target(uri)
                .queryParam("input", "hello")
                ;
        Response response = echoEndpointTarget.request(TEXT_PLAIN).header(HttpHeaders.AUTHORIZATION, "BASIC "+token).get();
        Assert.assertEquals(HttpURLConnection.HTTP_OK, response.getStatus());
        String reply = response.readEntity(String.class);
        Assert.assertEquals("hello, user=jdoe@example.com", reply);
    }

    @Test
    public void callEcho() throws Exception {
        System.out.printf("jwt: %s\n", token);

        String uri = baseURL.toExternalForm() + "/endp/echo";
        WebTarget echoEndpointTarget = ClientBuilder.newClient()
                .target(uri)
                .queryParam("input", "hello")
                ;
        Response response = echoEndpointTarget.request(TEXT_PLAIN).header(HttpHeaders.AUTHORIZATION, "Bearer "+token).get();
        Assert.assertEquals(HttpURLConnection.HTTP_OK, response.getStatus());
        String reply = response.readEntity(String.class);
        Assert.assertEquals("hello, user=jdoe@example.com", reply);
    }

    @Test
    public void callEcho2() throws Exception {
        System.out.printf("jwt: %s\n", token);

        String uri = baseURL.toExternalForm() + "/endp/echo2";
        WebTarget echoEndpointTarget = ClientBuilder.newClient()
                .target(uri)
                .queryParam("input", "hello")
                ;
        Response response = echoEndpointTarget.request(TEXT_PLAIN).header(HttpHeaders.AUTHORIZATION, "Bearer "+token).get();
        String reply = response.readEntity(String.class);
        Assert.assertEquals(HttpURLConnection.HTTP_FORBIDDEN, response.getStatus());
    }

    @Test
    public void getPrincipalClass() throws Exception {
        String uri = baseURL.toExternalForm() + "/endp/getPrincipalClass";
        WebTarget echoEndpointTarget = ClientBuilder.newClient()
                .target(uri)
                ;
        Response response = echoEndpointTarget.request(TEXT_PLAIN).header(HttpHeaders.AUTHORIZATION, "Bearer "+token).get();
        Assert.assertEquals(HttpURLConnection.HTTP_OK, response.getStatus());
        String reply = response.readEntity(String.class);
        String[] ifaces = reply.split(",");
        boolean hasJsonWebToken = false;
        for(String iface : ifaces) {
            hasJsonWebToken |= iface.equals(JsonWebToken.class.getTypeName());
        }
        Assert.assertTrue("PrincipalClass has JsonWebToken interface", hasJsonWebToken);
    }
    @Test
    public void getInjectedPrincipal() throws Exception {
        String uri = baseURL.toExternalForm() + "/endp/getInjectedPrincipal";
        WebTarget echoEndpointTarget = ClientBuilder.newClient()
                .target(uri)
                ;
        Response response = echoEndpointTarget.request(TEXT_PLAIN).header(HttpHeaders.AUTHORIZATION, "Bearer "+token).get();
        Assert.assertEquals(HttpURLConnection.HTTP_OK, response.getStatus());
        String reply = response.readEntity(String.class);
        String[] ifaces = reply.split(",");
        boolean hasJsonWebToken = false;
        for(String iface : ifaces) {
            hasJsonWebToken |= iface.equals(JsonWebToken.class.getTypeName());
        }
        Assert.assertTrue("PrincipalClass has JsonWebToken interface", hasJsonWebToken);
    }

    @Test
    public void getInjectedClaims() throws Exception {
        String uri = baseURL.toExternalForm() + "/endp/getInjectedClaims";
        WebTarget echoEndpointTarget = ClientBuilder.newClient()
                .target(uri)
                .queryParam(Claims.iss.name(), "https://server.example.com")
                .queryParam(Claims.jti.name(), "a-123")
                .queryParam(Claims.aud.name(), "s6BhdRkqt3")
                .queryParam(Claims.sub.name(), "24400320")
                .queryParam(Claims.groups.name(), "Echoer,Tester,group1,group2")
                .queryParam(Claims.raw_token.name(), token)
                .queryParam(Claims.iat.name(), iatClaim)
                .queryParam(Claims.auth_time.name(), authTimeClaim)
                ;
        Response response = echoEndpointTarget.request(TEXT_PLAIN).header(HttpHeaders.AUTHORIZATION, "Bearer "+token).get();
        Assert.assertEquals(HttpURLConnection.HTTP_OK, response.getStatus());
        String reply = response.readEntity(String.class);
        System.out.println(reply);
        Assert.assertTrue("has iss", reply.contains("iss PASS"));
        Assert.assertTrue("has jti", reply.contains("jti PASS"));
        Assert.assertTrue("has jti-Optional", reply.contains("jti-Optional PASS"));
        Assert.assertTrue("has jti-Provider", reply.contains("jti-Provider PASS"));
        Assert.assertTrue("has aud", reply.contains("aud PASS"));
        Assert.assertTrue("has iat", reply.contains("iat PASS"));
        Assert.assertTrue("has iat-Dupe", reply.contains("iat-Dupe PASS"));
        Assert.assertTrue("has sub-Optional", reply.contains("sub-Optional PASS"));
        Assert.assertTrue("has auth_time", reply.contains("auth_time PASS"));
        Assert.assertTrue("has raw_token", reply.contains("raw_token PASS"));
        Assert.assertTrue("saw custom-missing", reply.contains("custom-missing PASS"));
        Assert.assertTrue("has groups-Provider", reply.contains("groups-Provider PASS"));

        // A second request to validate the request scope of injected values
        HashMap<String, Long> timeClaims = new HashMap<>();
        String token2 = TokenUtils.generateTokenString("/RolesEndpoint2.json", null, timeClaims);
        Long iatClaim2 = timeClaims.get(Claims.iat.name());
        Long authTimeClaim2 = timeClaims.get(Claims.auth_time.name());
        WebTarget echoEndpointTarget2 = ClientBuilder.newClient()
                .target(uri)
                .queryParam(Claims.iss.name(), "https://server.example.com")
                .queryParam(Claims.jti.name(), "a-123.2")
                .queryParam(Claims.aud.name(), "s6BhdRkqt3")
                .queryParam(Claims.sub.name(), "24400320#2")
                .queryParam(Claims.groups.name(), "Echoer,Tester,group1.2,group2.2")
                .queryParam(Claims.raw_token.name(), token2)
                .queryParam(Claims.iat.name(), iatClaim2)
                .queryParam(Claims.auth_time.name(), authTimeClaim2)
                ;
        Response response2 = echoEndpointTarget2.request(TEXT_PLAIN).header(HttpHeaders.AUTHORIZATION, "Bearer "+token2).get();
        Assert.assertEquals(HttpURLConnection.HTTP_OK, response2.getStatus());
        reply = response2.readEntity(String.class);
        System.out.println(reply);
        Assert.assertTrue("has iss", reply.contains("iss PASS"));
        Assert.assertTrue("has jti", reply.contains("jti PASS"));
        Assert.assertTrue("has jti-Optional", reply.contains("jti-Optional PASS"));
        Assert.assertTrue("has jti-Provider", reply.contains("jti-Provider PASS"));
        Assert.assertTrue("has aud", reply.contains("aud PASS"));
        Assert.assertTrue("has iat", reply.contains("iat PASS"));
        Assert.assertTrue("has iat-Dupe", reply.contains("iat-Dupe PASS"));
        Assert.assertTrue("has sub-Optional", reply.contains("sub-Optional PASS"));
        Assert.assertTrue("has auth_time", reply.contains("auth_time PASS"));
        Assert.assertTrue("has raw_token", reply.contains("raw_token PASS"));
        Assert.assertTrue("saw custom-missing", reply.contains("custom-missing PASS"));
        Assert.assertTrue("has groups-Provider", reply.contains("groups-Provider PASS"));

    }

    @Test
    @Ignore("TODO: look into behavior of this test")
    public void getInjectedPrincipalNoAuth() throws Exception {
        String uri = baseURL.toExternalForm() + "/endp/getInjectedPrincipal";
        WebTarget echoEndpointTarget = ClientBuilder.newClient()
                .target(uri)
                ;
        Response response = echoEndpointTarget.request(TEXT_PLAIN).get();
        Assert.assertEquals(HttpURLConnection.HTTP_OK, response.getStatus());
        String reply = response.readEntity(String.class);
        Assert.assertEquals("PrincipalClass has JsonWebToken interface", "NO_INJECTED_PRINCIPAL", reply);
    }
    @Test
    public void getSubjectClass() throws Exception {
        String uri = baseURL.toExternalForm() + "/endp/getSubjectClass";
        WebTarget echoEndpointTarget = ClientBuilder.newClient()
                .target(uri)
                ;
        Response response = echoEndpointTarget.request(TEXT_PLAIN).header(HttpHeaders.AUTHORIZATION, "Bearer "+token).get();
        Assert.assertEquals(HttpURLConnection.HTTP_OK, response.getStatus());
        String reply = response.readEntity(String.class);
        System.out.println(reply);
    }

    @Test
    public void getServletPrincipalClass() throws Exception {
        String uri = baseURL.toExternalForm() + "/ServiceServlet/getPrincipalClass";
        WebTarget echoEndpointTarget = ClientBuilder.newClient()
                .target(uri)
                ;
        Response response = echoEndpointTarget.request(TEXT_PLAIN).header(HttpHeaders.AUTHORIZATION, "Bearer "+token).get();
        Assert.assertEquals(HttpURLConnection.HTTP_OK, response.getStatus());
        String reply = response.readEntity(String.class);
        String[] ifaces = reply.split(",");
        boolean hasJsonWebToken = false;
        for(String iface : ifaces) {
            hasJsonWebToken |= iface.equals(JsonWebToken.class.getTypeName());
        }
        Assert.assertTrue("PrincipalClass has JsonWebToken interface", hasJsonWebToken);
    }

    @Test
    public void getServletSubjectClass() throws Exception {
        String uri = baseURL.toExternalForm() + "/ServiceServlet/getSubject";
        WebTarget echoEndpointTarget = ClientBuilder.newClient()
                .target(uri)
                ;
        Response response = echoEndpointTarget.request(TEXT_PLAIN).header(HttpHeaders.AUTHORIZATION, "Bearer "+token).get();
        Assert.assertEquals(HttpURLConnection.HTTP_OK, response.getStatus());
        String reply = response.readEntity(String.class);
        System.out.println(reply);
    }

    @Test
    public void testEJBPrincipalClass() throws Exception {
        String uri = baseURL.toExternalForm() + "/endp/getEJBPrincipalClass";
        WebTarget echoEndpointTarget = ClientBuilder.newClient()
                .target(uri)
                ;
        Response response = echoEndpointTarget.request(TEXT_PLAIN).header(HttpHeaders.AUTHORIZATION, "Bearer "+token).get();
        Assert.assertEquals(HttpURLConnection.HTTP_OK, response.getStatus());
        String reply = response.readEntity(String.class);
        String[] ifaces = reply.split(",");
        boolean hasJsonWebToken = false;
        for(String iface : ifaces) {
            hasJsonWebToken |= iface.equals(JsonWebToken.class.getTypeName());
        }
        Assert.assertTrue("EJB PrincipalClass has JsonWebToken interface", hasJsonWebToken);
    }

    @Test
    public void getEJBSubjectClass() throws Exception {
        String uri = baseURL.toExternalForm() + "/endp/getEJBSubjectClass";
        WebTarget echoEndpointTarget = ClientBuilder.newClient()
                .target(uri)
                ;
        Response response = echoEndpointTarget.request(TEXT_PLAIN).header(HttpHeaders.AUTHORIZATION, "Bearer "+token).get();
        Assert.assertEquals(HttpURLConnection.HTTP_OK, response.getStatus());
        String reply = response.readEntity(String.class);
        System.out.println(reply);
    }

    /**
     * This test requires that the server provide a mapping from the group1 grant in the token to a Group1MappedRole
     * application declared role.
     */
    @Test
    public void testNeedsGroup1Mapping() {
        String uri = baseURL.toExternalForm() + "/endp/needsGroup1Mapping";
        WebTarget echoEndpointTarget = ClientBuilder.newClient()
                .target(uri)
                ;
        Response response = echoEndpointTarget.request(TEXT_PLAIN).header(HttpHeaders.AUTHORIZATION, "Bearer "+token).get();
        Assert.assertEquals(HttpURLConnection.HTTP_OK, response.getStatus());
        String reply = response.readEntity(String.class);
        System.out.println(reply);
    }

    @Test
    public void callHeartbeat() throws Exception {
        String uri = baseURL.toExternalForm() + "/endp/heartbeat";
        WebTarget echoEndpointTarget = ClientBuilder.newClient()
                .target(uri)
                .queryParam("input", "hello")
                ;
        Response response = echoEndpointTarget.request(TEXT_PLAIN).get();
        Assert.assertEquals(HttpURLConnection.HTTP_OK, response.getStatus());
        Assert.assertTrue("Heartbeat:", response.readEntity(String.class).startsWith("Heartbeat:"));
    }
}
