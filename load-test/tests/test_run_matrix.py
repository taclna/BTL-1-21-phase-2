import importlib.util
import pathlib
import tempfile
import unittest
from unittest import mock


MODULE_PATH = pathlib.Path(__file__).resolve().parents[1] / "run-matrix.py"
SPEC = importlib.util.spec_from_file_location("run_matrix", MODULE_PATH)
run_matrix = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(run_matrix)


class RunMatrixTest(unittest.TestCase):

    def test_safe_name_normalizes_branch_separators(self):
        self.assertEqual(
            "feature-phase2-idempotency",
            run_matrix.safe_name("feature/phase2-idempotency"),
        )

    def test_resolve_ref_finds_baseline_tag(self):
        expected = run_matrix.git_value(
            run_matrix.HARNESS_ROOT,
            "rev-parse",
            "phase-1-baseline^{commit}",
        )
        self.assertEqual(
            expected,
            run_matrix.resolve_ref(run_matrix.HARNESS_ROOT, "phase-1-baseline"),
        )

    @mock.patch.object(run_matrix, "git_value", return_value="")
    @mock.patch.object(run_matrix, "run")
    def test_fresh_app_checkout_uses_a_normal_clone(self, run_command, _git_value):
        with tempfile.TemporaryDirectory() as temporary_directory:
            app_dir = pathlib.Path(temporary_directory) / "app"

            def create_checkout(command, **_kwargs):
                if command[:2] == ["git", "clone"]:
                    (app_dir / ".git").mkdir(parents=True)

            run_command.side_effect = create_checkout
            run_matrix.ensure_app_checkout("https://example.test/repo.git", app_dir)

        clone_command = run_command.call_args_list[0].args[0]
        self.assertEqual(["git", "clone"], clone_command[:2])
        self.assertNotIn("--no-checkout", clone_command)

    @mock.patch.object(run_matrix, "run")
    @mock.patch.object(
        run_matrix,
        "postgres_values",
        return_value=("db.example.com", "5432", "cinema", "cinema"),
    )
    def test_database_reset_rejects_non_load_test_database(self, _values, run_command):
        with self.assertRaisesRegex(RuntimeError, "refusing to reset"):
            run_matrix.reset_test_schema("psql")
        run_command.assert_not_called()

    @mock.patch.dict("os.environ", {"PGPASSWORD": "secret"}, clear=False)
    @mock.patch.object(run_matrix, "run")
    @mock.patch.object(
        run_matrix,
        "postgres_values",
        return_value=("127.0.0.1", "5432", "cinema_load_e7", "cinema_load"),
    )
    def test_database_reset_allows_local_load_test_database(self, _values, run_command):
        run_matrix.reset_test_schema("custom-psql")
        command = run_command.call_args.args[0]
        environment = run_command.call_args.kwargs["env"]
        self.assertEqual("custom-psql", command[0])
        self.assertIn("DROP SCHEMA IF EXISTS public CASCADE", command[-1])
        self.assertEqual("phase2-matrix", environment["PGAPPNAME"])


if __name__ == "__main__":
    unittest.main()
