/**
 * © Nowina Solutions, 2015-2015
 *
 * Concédée sous licence EUPL, version 1.1 ou – dès leur approbation par la Commission européenne - versions ultérieures de l’EUPL (la «Licence»).
 * Vous ne pouvez utiliser la présente œuvre que conformément à la Licence.
 * Vous pouvez obtenir une copie de la Licence à l’adresse suivante:
 *
 * http://ec.europa.eu/idabc/eupl5
 *
 * Sauf obligation légale ou contractuelle écrite, le logiciel distribué sous la Licence est distribué «en l’état»,
 * SANS GARANTIES OU CONDITIONS QUELLES QU’ELLES SOIENT, expresses ou implicites.
 * Consultez la Licence pour les autorisations et les restrictions linguistiques spécifiques relevant de la Licence.
 */
package lu.nowina.nexu;

import static org.mockito.Mockito.mock;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import lu.nowina.nexu.api.AppConfig;
import lu.nowina.nexu.api.Arch;
import lu.nowina.nexu.api.NexuAPI;
import lu.nowina.nexu.api.ProductAdapter;
import lu.nowina.nexu.api.DetectedCard;
import lu.nowina.nexu.api.EnvironmentInfo;
import lu.nowina.nexu.api.Match;
import lu.nowina.nexu.api.ScAPI;
import lu.nowina.nexu.generic.ConnectionInfo;
import lu.nowina.nexu.generic.SCDatabase;
import lu.nowina.nexu.generic.SCInfo;
import lu.nowina.nexu.view.core.UIDisplay;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import eu.europa.esig.dss.token.DSSPrivateKeyEntry;
import eu.europa.esig.dss.token.SignatureTokenConnection;

public class InternalAPITest {

	@Test
	public void testEnvironment() throws Exception {

		InternalAPI api = new InternalAPI(null, null, null, null, null, mock(AppConfig.class));

		EnvironmentInfo info = api.getEnvironmentInfo();
		Assert.assertNotNull(info.getOs());
		Assert.assertNotNull(info.getArch());
		Assert.assertNotNull(info.getOsName());
		Assert.assertNotNull(info.getOsVersion());
		Assert.assertNotNull(info.getJreVendor());

	}

	/**
	 * No card are detected
	 * 
	 * @throws Exception
	 */
	@Test
	public void testDetectCards1() throws Exception {

		CardDetector detector = Mockito.mock(CardDetector.class);

		UIDisplay display = Mockito.mock(UIDisplay.class);

		InternalAPI api = new InternalAPI(display, null, detector, null, null, mock(AppConfig.class));
		Assert.assertEquals(0, api.detectCards().size());

	}

	@Test
	public void testDetectCards2() throws Exception {

		CardDetector detector = Mockito.mock(CardDetector.class);
		Mockito.when(detector.detectCard()).thenReturn(Arrays.asList(new DetectedCard("ATR", 0)));

		UIDisplay display = Mockito.mock(UIDisplay.class);

		InternalAPI api = new InternalAPI(display, null, detector, null, null, mock(AppConfig.class));
		Assert.assertEquals(1, api.detectCards().size());

	}

	@Test
	public void testDetectCards3() throws Exception {

		CardDetector detector = Mockito.mock(CardDetector.class);
		Mockito.when(detector.detectCard()).thenReturn(Arrays.asList(new DetectedCard("ATR1", 0), new DetectedCard("ATR2", 0)));

		UIDisplay display = Mockito.mock(UIDisplay.class);

		InternalAPI api = new InternalAPI(display, null, detector, null, null, mock(AppConfig.class));
		Assert.assertEquals(2, api.detectCards().size());

	}

	@Test
	public void testMatchingProductAdapter1() throws Exception {

		DetectedCard card = new DetectedCard("ATR", 0);

		UIDisplay display = Mockito.mock(UIDisplay.class);
		InternalAPI api = new InternalAPI(display, null, null, null, null, mock(AppConfig.class));

		SignatureTokenConnection c = new MockSignatureTokenConnection((DSSPrivateKeyEntry[])null);
		api.registerTokenConnection(c);

		ProductAdapter adapter1 = Mockito.mock(ProductAdapter.class);
		Mockito.when(adapter1.accept(card)).thenReturn(Boolean.TRUE);
		Mockito.when(adapter1.connect(api, card, display.getPasswordInputCallback())).thenReturn(c);

		List<Match> matches = api.matchingProductAdapters(card);

		Assert.assertEquals(0, matches.size());

		api.registerProductAdapter(adapter1);

		matches = api.matchingProductAdapters(card);

		Assert.assertEquals(1, matches.size());

	}

	@Test
	public void testMatchingProductAdaptersRemovesStalePkcs11Mapping() throws Exception {

		final SCDatabase db = new SCDatabase();
		final File missingDll = new File(System.getProperty("java.io.tmpdir"),
				"nexu-test-missing-" + System.nanoTime() + ".dll");
		Assert.assertFalse(missingDll.exists());
		db.add("ATR-STALE", buildConnectionInfo(ScAPI.PKCS_11, missingDll.getAbsolutePath()));

		InternalAPI api = new InternalAPI(mock(UIDisplay.class), db, null, null, null, mock(AppConfig.class));

		final List<Match> matches = api.matchingProductAdapters(new DetectedCard("ATR-STALE", 0));

		// The stale mapping is removed, so the card is not matched and the next request
		// misses the database lookup and re-enters advanced creation.
		Assert.assertEquals(0, matches.size());
		Assert.assertNull(db.getInfo("ATR-STALE"));
	}

	@Test
	public void testMatchingProductAdaptersKeepsExistingPkcs11Mapping() throws Exception {

		final SCDatabase db = new SCDatabase();
		final File dll = File.createTempFile("nexu-test-existing-", ".dll");
		dll.deleteOnExit();
		db.add("ATR-OK", buildConnectionInfo(ScAPI.PKCS_11, dll.getAbsolutePath()));

		InternalAPI api = new InternalAPI(mock(UIDisplay.class), db, null, null, null, mock(AppConfig.class));

		final List<Match> matches = api.matchingProductAdapters(new DetectedCard("ATR-OK", 0));

		Assert.assertEquals(1, matches.size());
		Assert.assertEquals(1, db.getInfo("ATR-OK").getInfos().size());
	}

	@Test
	public void testMatchingProductAdaptersKeepsNonMatchingEntries() throws Exception {

		final SCDatabase db = new SCDatabase();
		final File missingDll = new File(System.getProperty("java.io.tmpdir"),
				"nexu-test-missing-" + System.nanoTime() + ".dll");
		Assert.assertFalse(missingDll.exists());
		db.add("ATR-MIX", buildConnectionInfo(ScAPI.PKCS_11, missingDll.getAbsolutePath()));

		// MSCAPI entries (null apiParam) are never considered stale.
		db.add("ATR-MIX", buildConnectionInfo(ScAPI.MSCAPI, null));

		// A PKCS#11 entry for a different environment is not considered on this machine.
		final EnvironmentInfo otherEnv = EnvironmentInfo.buildFromSystemProperties(System.getProperties());
		otherEnv.setArch(otherEnv.getArch() == Arch.X86 ? Arch.AMD64 : Arch.X86);
		final ConnectionInfo otherEnvCInfo = buildConnectionInfo(ScAPI.PKCS_11, missingDll.getAbsolutePath());
		otherEnvCInfo.setEnv(otherEnv);
		db.add("ATR-MIX", otherEnvCInfo);

		InternalAPI api = new InternalAPI(mock(UIDisplay.class), db, null, null, null, mock(AppConfig.class));

		final List<Match> matches = api.matchingProductAdapters(new DetectedCard("ATR-MIX", 0));

		// Only the stale entry for the current environment is removed.
		Assert.assertEquals(1, matches.size());
		Assert.assertEquals(2, db.getInfo("ATR-MIX").getInfos().size());
	}

	@Test
	public void testStore() throws Exception {

		final SCDatabase db = new SCDatabase();
		InternalAPI api = new InternalAPI(mock(UIDisplay.class), db, null, null, null, mock(AppConfig.class));

		// store is part of the public NexuAPI contract.
		final NexuAPI publicApi = api;
		publicApi.store("ATR-STORED", ScAPI.PKCS_11, "C:\\Program Files\\middleware\\pkcs11.dll");

		final SCInfo info = db.getInfo("ATR-STORED");
		Assert.assertNotNull(info);
		Assert.assertEquals(1, info.getInfos().size());
		final ConnectionInfo stored = info.getInfos().get(0);
		Assert.assertEquals(ScAPI.PKCS_11, stored.getSelectedApi());
		Assert.assertEquals("C:\\Program Files\\middleware\\pkcs11.dll", stored.getApiParam());
		Assert.assertEquals(api.getEnvironmentInfo(), stored.getEnv());

		// Storing the same selection again is deduplicated.
		publicApi.store("ATR-STORED", ScAPI.PKCS_11, "C:\\Program Files\\middleware\\pkcs11.dll");
		Assert.assertEquals(1, db.getInfo("ATR-STORED").getInfos().size());
	}

	@Test
	public void testRemoveConnectionInfo() throws Exception {

		final SCDatabase db = new SCDatabase();
		InternalAPI api = new InternalAPI(mock(UIDisplay.class), db, null, null, null, mock(AppConfig.class));

		api.store("ATR-REMOVE", ScAPI.PKCS_11, "C:\\middleware\\pkcs11.dll");
		api.store("ATR-REMOVE", ScAPI.MSCAPI, null);

		api.removeConnectionInfo("ATR-REMOVE", ScAPI.PKCS_11, "C:\\middleware\\pkcs11.dll");
		Assert.assertEquals(1, db.getInfo("ATR-REMOVE").getInfos().size());
		Assert.assertEquals(ScAPI.MSCAPI, db.getInfo("ATR-REMOVE").getInfos().get(0).getSelectedApi());

		// Removing an unknown mapping is a no-op.
		api.removeConnectionInfo("ATR-REMOVE", ScAPI.PKCS_11, "C:\\middleware\\other.dll");
		Assert.assertEquals(1, db.getInfo("ATR-REMOVE").getInfos().size());
	}

	private static ConnectionInfo buildConnectionInfo(ScAPI api, String apiParam) {
		final ConnectionInfo cInfo = new ConnectionInfo();
		cInfo.setSelectedApi(api);
		cInfo.setApiParam(apiParam);
		cInfo.setEnv(EnvironmentInfo.buildFromSystemProperties(System.getProperties()));
		return cInfo;
	}

}
