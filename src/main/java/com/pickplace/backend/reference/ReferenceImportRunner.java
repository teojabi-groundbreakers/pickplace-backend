package com.pickplace.backend.reference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("reference-import")
public class ReferenceImportRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(ReferenceImportRunner.class);
	private final ReferenceSnapshotLoader loader;
	private final ReferenceDataImporter importer;
	private final boolean confirmed;

	public ReferenceImportRunner(ReferenceSnapshotLoader loader, ReferenceDataImporter importer,
			@Value("${pickplace.reference-import.confirm:false}") boolean confirmed) {
		this.loader = loader;
		this.importer = importer;
		this.confirmed = confirmed;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!confirmed) throw new IllegalStateException("Reference import requires --pickplace.reference-import.confirm=true");
		var snapshot = loader.load();
		var result = importer.importSnapshot(snapshot);
		log.info("Reference import committed: version={}, insertedDistricts={}, insertedDongs={}, insertedBusinessTypes={}",
				snapshot.version(), result.districtsInserted(), result.dongsInserted(), result.businessTypesInserted());
	}
}
