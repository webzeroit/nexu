/**
 * © Nowina Solutions, 2015-2015
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

import javax.xml.bind.JAXBContext;

import org.junit.Assert;
import org.junit.Test;

import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import lu.nowina.nexu.DatabaseEventHandler;
import lu.nowina.nexu.ProductDatabase;
import lu.nowina.nexu.api.EnvironmentInfo;
import lu.nowina.nexu.api.ScAPI;

public class SCDatabaseTest {

	@Test
	public void test1() throws Exception {

		SCDatabase db = new SCDatabase();

		ConnectionInfo cInfo = new ConnectionInfo();
		cInfo.setApiParam("param");
		cInfo.setSelectedApi(ScAPI.MSCAPI);
		cInfo.setEnv(EnvironmentInfo.buildFromSystemProperties(System.getProperties()));
		db.add("ADSF123FSDFS", cInfo);

		db.getInfo("ADSF123FSDFS").getSupportedDigestAlgorithm().add(DigestAlgorithm.SHA1);
		db.getInfo("ADSF123FSDFS").getSupportedDigestAlgorithm().add(DigestAlgorithm.MD5);

		JAXBContext ctx = JAXBContext.newInstance(SCDatabase.class);
		ctx.createMarshaller().marshal(db, System.out);

	}

	@Test
	public void test2() throws Exception {

		SCDatabase db = new SCDatabase();
		final int[] saveCount = { 0 };
		db.setOnAddRemoveAction(new DatabaseEventHandler() {
			@Override
			public <T extends ProductDatabase> void execute(T database) {
				saveCount[0]++;
			}
		});

		ConnectionInfo cInfo = buildConnectionInfo(ScAPI.MSCAPI, "param");
		db.add("ATR1", cInfo);

		Assert.assertEquals(1, db.getSmartcards().size());
		Assert.assertEquals(1, db.getSmartcards().get(0).getInfos().size());
		Assert.assertEquals(1, saveCount[0]);

		// A ConnectionInfo with the same selectedApi, apiParam and env for the same ATR is
		// ignored: nothing is added, nothing is saved.
		db.add("ATR1", buildConnectionInfo(ScAPI.MSCAPI, "param"));

		Assert.assertEquals(1, db.getSmartcards().size());
		Assert.assertEquals(1, db.getSmartcards().get(0).getInfos().size());
		Assert.assertEquals(1, saveCount[0]);

		// A different apiParam for the same ATR is a distinct entry.
		db.add("ATR1", buildConnectionInfo(ScAPI.MSCAPI, "param2"));

		Assert.assertEquals(1, db.getSmartcards().size());
		Assert.assertEquals(2, db.getSmartcards().get(0).getInfos().size());
		Assert.assertEquals(2, saveCount[0]);

		db.add("ATR2", buildConnectionInfo(ScAPI.MSCAPI, "param"));

		Assert.assertEquals(2, db.getSmartcards().size());
		Assert.assertEquals(2, db.getSmartcards().get(0).getInfos().size());
		Assert.assertEquals(1, db.getSmartcards().get(1).getInfos().size());
		Assert.assertTrue(db.getInfo("ATR1") == db.getSmartcards().get(0));
		Assert.assertTrue(db.getInfo("ATR2") == db.getSmartcards().get(1));

	}

	@Test
	public void testDifferentEnvironmentIsNotADuplicate() {

		SCDatabase db = new SCDatabase();
		EnvironmentInfo env = EnvironmentInfo.buildFromSystemProperties(System.getProperties());
		EnvironmentInfo otherEnv = EnvironmentInfo.buildFromSystemProperties(System.getProperties());
		otherEnv.setOsVersion(env.getOsVersion() + "-other");

		ConnectionInfo cInfo = buildConnectionInfo(ScAPI.PKCS_11, "C:\\pkcs11.dll");
		cInfo.setEnv(env);
		db.add("ATR1", cInfo);

		ConnectionInfo cInfo2 = buildConnectionInfo(ScAPI.PKCS_11, "C:\\pkcs11.dll");
		cInfo2.setEnv(otherEnv);
		db.add("ATR1", cInfo2);

		Assert.assertEquals(1, db.getSmartcards().size());
		Assert.assertEquals(2, db.getSmartcards().get(0).getInfos().size());

	}

	@Test
	public void testDifferentApiIsNotADuplicate() {

		SCDatabase db = new SCDatabase();
		db.add("ATR1", buildConnectionInfo(ScAPI.PKCS_11, "param"));
		db.add("ATR1", buildConnectionInfo(ScAPI.MSCAPI, "param"));

		Assert.assertEquals(1, db.getSmartcards().size());
		Assert.assertEquals(2, db.getSmartcards().get(0).getInfos().size());

	}

	@Test
	public void testRemoveConnectionInfo() {

		SCDatabase db = new SCDatabase();
		final int[] saveCount = { 0 };
		db.setOnAddRemoveAction(new DatabaseEventHandler() {
			@Override
			public <T extends ProductDatabase> void execute(T database) {
				saveCount[0]++;
			}
		});

		ConnectionInfo cInfo1 = buildConnectionInfo(ScAPI.PKCS_11, "C:\\first.dll");
		ConnectionInfo cInfo2 = buildConnectionInfo(ScAPI.PKCS_11, "C:\\second.dll");
		db.add("ATR1", cInfo1);
		db.add("ATR1", cInfo2);
		int savedAfterAdd = saveCount[0];

		// Removing a non-matching ConnectionInfo changes nothing and does not save.
		Assert.assertFalse(db.removeConnectionInfo("ATR1", buildConnectionInfo(ScAPI.PKCS_11, "C:\\unknown.dll")));
		Assert.assertEquals(2, db.getSmartcards().get(0).getInfos().size());
		Assert.assertEquals(savedAfterAdd, saveCount[0]);

		// Removing one entry keeps the SCInfo.
		Assert.assertTrue(db.removeConnectionInfo("ATR1", cInfo1));
		Assert.assertEquals(1, db.getSmartcards().get(0).getInfos().size());
		Assert.assertEquals("C:\\second.dll", db.getSmartcards().get(0).getInfos().get(0).getApiParam());
		Assert.assertEquals(savedAfterAdd + 1, saveCount[0]);

		// Removing the last entry removes the whole SCInfo, so the next lookup misses the database.
		Assert.assertTrue(db.removeConnectionInfo("ATR1", cInfo2));
		Assert.assertNull(db.getInfo("ATR1"));
		Assert.assertEquals(0, db.getSmartcards().size());
		Assert.assertEquals(savedAfterAdd + 2, saveCount[0]);

		// Removing again is a no-op.
		Assert.assertFalse(db.removeConnectionInfo("ATR1", cInfo2));

	}

	private static ConnectionInfo buildConnectionInfo(ScAPI api, String apiParam) {
		ConnectionInfo cInfo = new ConnectionInfo();
		cInfo.setSelectedApi(api);
		cInfo.setApiParam(apiParam);
		cInfo.setEnv(EnvironmentInfo.buildFromSystemProperties(System.getProperties()));
		return cInfo;
	}

}
