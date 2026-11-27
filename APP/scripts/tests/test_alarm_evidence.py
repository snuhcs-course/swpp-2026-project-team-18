"""Synthetic fixtures exercise the analyzer; these are not device evidence."""
import importlib.util
import unittest
from datetime import datetime, timedelta
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[1] / "analyze_alarm_evidence.py"
SPEC = importlib.util.spec_from_file_location("alarm_evidence", SOURCE)
analysis = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(analysis)


class AlarmEvidenceTest(unittest.TestCase):
    cutoff = datetime.fromisoformat("2026-12-04T23:59:59+09:00")

    def row(self, day=28, delta=0, **overrides):
        scheduled = datetime.fromisoformat(f"2026-11-{day:02}T07:30:00+09:00")
        row = {
            "device_id": "fixture-device", "app_version": "fixture-version", "event_id": str(day),
            "scheduled_at": scheduled.isoformat(),
            "fired_at": (scheduled + timedelta(seconds=delta)).isoformat() if delta is not None else "",
            "expected": "true", "verified_at": "", "evidence": "fixture-log", "notes": "",
        }
        row.update(overrides)
        return row

    def test_inclusive_five_second_boundary_and_signed_delta(self):
        result = analysis.analyze_alarms([
            self.row(27, -5), self.row(28, 5), self.row(29, 5.001), self.row(30, -5.001),
        ], self.cutoff)
        self.assertEqual(["on_time", "on_time", "late", "early"], [r["status"] for r in result["observations"]])
        self.assertAlmostEqual(-5.001, result["observations"][3]["delta_seconds"])
        self.assertEqual("fail", result["n2"])

    def test_missing_log_needs_manual_confirmation_and_evidence(self):
        result = analysis.analyze_alarms([
            self.row(27, None),
            self.row(28, None, verified_at="2026-11-28T07:31:00+09:00"),
        ], self.cutoff)
        self.assertEqual(1, result["counts"]["unverified_missing"])
        self.assertEqual(1, result["counts"]["missing"])
        self.assertEqual("fail", result["n1"])
        self.assertEqual("fail", result["n2"])

    def test_verification_before_alarm_does_not_confirm_a_miss(self):
        result = analysis.analyze_alarms([
            self.row(28, None, verified_at="2026-11-28T07:00:00+09:00"),
        ], self.cutoff)
        self.assertEqual("unverified_missing", result["observations"][0]["status"])
        self.assertEqual("incomplete", result["n1"])

    def test_future_and_five_second_grace_window_are_pending(self):
        result = analysis.analyze_alarms([
            self.row(28, None, scheduled_at="2026-12-05T07:30:00+09:00"),
            self.row(29, None, scheduled_at="2026-12-04T23:59:56+09:00"),
        ], self.cutoff)
        self.assertEqual(2, result["counts"]["pending"])
        self.assertEqual(0, result["counts"]["due"])
        self.assertEqual("incomplete", result["n2"])

    def test_cancelled_schedule_excluded_but_unexpected_firing_fails(self):
        result = analysis.analyze_alarms([
            self.row(27, None, expected="false"), self.row(28, 0, expected="false"),
        ], self.cutoff)
        self.assertEqual(1, result["counts"]["excluded"])
        self.assertEqual(1, result["counts"]["unexpected_firing"])
        self.assertEqual("fail", result["n1"])

    def test_streak_is_per_device_and_version_not_pooled(self):
        rows = [self.row(day) for day in range(24, 31)]
        rows[3]["app_version"] = "other-version"
        result = analysis.analyze_alarms(rows, self.cutoff)
        self.assertEqual("incomplete", result["n1"])
        self.assertEqual(3, max(item["longest_consecutive_days"] for item in result["device_periods"]))
        rows[3]["app_version"] = "fixture-version"
        result = analysis.analyze_alarms(rows, self.cutoff)
        self.assertEqual("pass_observed_period", result["n1"])
        self.assertEqual("pass_observed_alarms", result["n2"])
        self.assertIn("does not establish the 30-day", result["limitation"])

    def test_fired_without_evidence_is_incomplete(self):
        result = analysis.analyze_alarms([self.row(evidence="")], self.cutoff, required_days=1)
        self.assertEqual("incomplete", result["n1"])
        self.assertEqual("incomplete", result["n2"])

    def test_duplicates_naive_times_and_future_evidence_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "duplicate"):
            analysis.analyze_alarms([self.row(), self.row()], self.cutoff)
        with self.assertRaisesRegex(ValueError, "timezone"):
            analysis.analyze_alarms([self.row(scheduled_at="2026-11-28T07:30:00")], self.cutoff)
        with self.assertRaisesRegex(ValueError, "after as_of"):
            analysis.analyze_alarms([self.row(fired_at="2026-12-05T07:30:00+09:00")], self.cutoff)

    def test_empty_templates_do_not_pass(self):
        result = analysis.analyze_alarms([], self.cutoff)
        self.assertEqual("incomplete", result["n1"])
        self.assertEqual("incomplete", result["n2"])
        self.assertEqual("incomplete", analysis.analyze_battery([])["n11"])


class BatteryEvidenceTest(unittest.TestCase):
    def row(self, day="2026-11-28", tracked="true", percent="2.5", **overrides):
        row = {
            "device_id": "fixture-device", "app_version": "fixture-version", "date": day,
            "tracking_enabled": tracked, "app_battery_percent": percent, "period_hours": "24",
            "charged": "false", "measurement_basis": "absolute_daily_percent", "evidence": "fixture-screen", "notes": "",
        }
        row.update(overrides)
        return row

    def test_pair_compares_tracking_and_baseline_and_uses_max_for_limit(self):
        baseline = self.row("2026-11-27", "false", "1.0")
        result = analysis.analyze_battery([baseline, self.row(percent="3.0")])
        self.assertEqual("pass_observed_days", result["n11"])
        self.assertEqual(1.0, result["comparisons"][0]["baseline_mean_percent"])
        result = analysis.analyze_battery([baseline, self.row(), self.row("2026-11-29", percent="3.1")])
        self.assertEqual("fail", result["n11"])

    def test_relative_usage_short_day_charge_and_missing_evidence_do_not_pass(self):
        for extra in (
            {"measurement_basis": "relative_usage_share"}, {"period_hours": "8"}, {"period_hours": "48"},
            {"charged": "true"}, {"evidence": ""},
        ):
            with self.subTest(extra=extra):
                result = analysis.analyze_battery([self.row(**extra)])
                self.assertEqual("incomplete", result["n11"])
                self.assertEqual(1, len(result["excluded_rows"]))

    def test_unpaired_devices_do_not_form_comparison_and_duplicates_rejected(self):
        result = analysis.analyze_battery([self.row(), self.row("2026-11-27", "false", device_id="other-device")])
        self.assertEqual("incomplete", result["n11"])
        with self.assertRaisesRegex(ValueError, "duplicate"):
            analysis.analyze_battery([self.row(), self.row()])

    def test_nonfinite_and_out_of_range_values_rejected(self):
        for percent in ("NaN", "Infinity", "-1", "101"):
            with self.subTest(percent=percent), self.assertRaises(ValueError):
                analysis.analyze_battery([self.row(percent=percent)])


if __name__ == "__main__":
    unittest.main()
