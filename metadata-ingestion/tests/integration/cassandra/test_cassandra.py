import pytest

from datahub.ingestion.run.pipeline import Pipeline
from tests.test_helpers import mce_helpers
from tests.test_helpers.docker_helpers import wait_for_port


@pytest.mark.integration
def test_cassandra_ingest(docker_compose_runner, pytestconfig, tmp_path):
    test_resources_dir = pytestconfig.rootpath / "tests/integration/cassandra"

    with docker_compose_runner(
        test_resources_dir / "docker-compose.yml", "cassandra"
    ) as docker_services:
        wait_for_port(docker_services, "testcassandra", 10350)

        # Run the metadata ingestion pipeline.
        pipeline = Pipeline.create(
            {
                "run_id": "cassandra-test",
                "source": {
                    "type": "cassandra",
                    "config": {
                        "contact_point": "localhost",
                        "port": 10350,
                    },
                },
                "sink": {
                    "type": "file",
                    "config": {
                        "filename": f"{tmp_path}/cassandra_mcps.json",
                    },
                },
            }
        )
        pipeline.run()
        pipeline.raise_from_status()

        # Verify the output.
        mce_helpers.check_golden_file(
            pytestconfig,
            output_path=tmp_path / "cassandra_mcps.json",
            golden_path=test_resources_dir / "cassandra_mcps_golden.json",
        )
