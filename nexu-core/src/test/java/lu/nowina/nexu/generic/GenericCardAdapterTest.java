/**
 * © Nowina Solutions, 2015-2016
 *
 * Concédée sous licence EUPL, version 1.1 ou – dès leur approbation par la Commission européenne - versions ultérieures de l’EUPL (la «Licence»).
 * Vous ne pouvez utiliser la présente œuvre que conformément à la Licence.
 * Vous pouvez obtenir une copie de la Licence à l’adresse suivante:
 *
 * http://ec.europa.eu/idabc/eupl5
 *
 * Sauf obligation légale ou contractuelle écrite, le logiciel distribué sous la Licence est distribué «en l’état»,
 * SANS GARANTIES OU CONDITIONS QUELLES QU’ELLES SOIENT, expresses ou implicites.
 * Consultez la Licence pour les autorisations et les restrictions linguistiques spécifiques relevant de la Licence.
 */
package lu.nowina.nexu.generic;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;

import org.junit.Assert;
import org.junit.Test;

import eu.europa.esig.dss.token.SignatureTokenConnection;
import lu.nowina.nexu.api.DetectedCard;
import lu.nowina.nexu.api.EnvironmentInfo;
import lu.nowina.nexu.api.NexuAPI;
import lu.nowina.nexu.api.ScAPI;

public class GenericCardAdapterTest {

	@Test
	public void connectReturnsNullWhenStoredPkcs11LibraryIsMissing() {

		final File missingDll = new File(System.getProperty("java.io.tmpdir"),
				"nexu-test-missing-" + System.nanoTime() + ".dll");
		Assert.assertFalse(missingDll.exists());

		final GenericCardAdapter adapter = buildAdapter(ScAPI.PKCS_11, missingDll.getAbsolutePath());
		final NexuAPI api = mockApi();

		// Stale DLL path (e.g. middleware reinstalled to another location): connect must not
		// fail, it must return null so the card is reported as NO_TOKEN.
		final SignatureTokenConnection token = adapter.connect(api, new DetectedCard("ATR-TEST", 0), null);

		Assert.assertNull(token);
	}

	@Test
	public void connectReturnsNullWhenStoredPkcs11ParameterIsNull() {

		final GenericCardAdapter adapter = buildAdapter(ScAPI.PKCS_11, null);
		final NexuAPI api = mockApi();

		final SignatureTokenConnection token = adapter.connect(api, new DetectedCard("ATR-TEST", 0), null);

		Assert.assertNull(token);
	}

	@Test
	public void connectLoadsPkcs11WhenStoredLibraryExists() throws Exception {

		final File dll = File.createTempFile("nexu-test-existing-", ".dll");
		dll.deleteOnExit();
		try {
			final GenericCardAdapter adapter = buildAdapter(ScAPI.PKCS_11, dll.getAbsolutePath());
			final NexuAPI api = mockApi();

			final SignatureTokenConnection token = adapter.connect(api, new DetectedCard("ATR-TEST", 0), null);

			Assert.assertNotNull(token);
			Assert.assertTrue(token instanceof Pkcs11SignatureTokenAdapter);
		} finally {
			dll.delete();
		}
	}

	private static GenericCardAdapter buildAdapter(ScAPI api, String apiParam) {
		final SCInfo info = new SCInfo();
		info.setAtr("ATR-TEST");
		final ConnectionInfo cInfo = new ConnectionInfo();
		cInfo.setSelectedApi(api);
		cInfo.setApiParam(apiParam);
		cInfo.setEnv(EnvironmentInfo.buildFromSystemProperties(System.getProperties()));
		info.getInfos().add(cInfo);
		return new GenericCardAdapter(info);
	}

	private static NexuAPI mockApi() {
		final NexuAPI api = mock(NexuAPI.class);
		when(api.getEnvironmentInfo()).thenReturn(EnvironmentInfo.buildFromSystemProperties(System.getProperties()));
		return api;
	}

}
