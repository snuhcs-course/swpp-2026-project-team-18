"""N26: one explicit test-account policy for telemetry, training, and cleanup.

Only reserved email prefixes are excluded, case-insensitively. A real account is
not excluded for its nickname, email domain, staff flag, or sparse activity.
Synthetic observations for a deliberately chosen demo account remain usable by
seed_synthetic --verify; they are not silently reclassified by this policy.
"""

from __future__ import annotations

from django.contrib.auth import get_user_model
from django.db.models import Q

# Existing deployment/API verification scripts, plus the team's QA convention.
# Add new verification account prefixes here rather than in individual consumers.
TEST_ACCOUNT_EMAIL_PREFIXES = (
    "qa-ui-", "qa-ui_", "qa-", "qa_", "e2e-", "e2e_",
    "depcheck_", "dbprobe_", "tester_", "ev_a_", "ev_b_",
    "obs_a_", "obs_b_", "rt_", "origin_", "tz_",
    "ctrchk_", "blkchk_", "emuchk_",
)


def test_account_filter(*, email_field="email") -> Q:
    """Predicate shared with the opt-in account cleanup script."""
    predicate = Q()
    for prefix in TEST_ACCOUNT_EMAIL_PREFIXES:
        predicate |= Q(**{f"{email_field}__istartswith": prefix})
    return predicate


# This is a predicate helper, not a pytest test when imported into a test module.
test_account_filter.__test__ = False


def production_users():
    """Lazy User queryset eligible for production aggregates and learning."""
    return get_user_model().objects.exclude(test_account_filter())


def eligible_user_ids():
    """Lazy, unordered id subquery; never materializes user identities in output."""
    return production_users().order_by().values_list("pk", flat=True)


def exclude_test_accounts(queryset, *, user_field="user"):
    """Keep records owned by eligible users (anonymous rows are not included)."""
    return queryset.filter(**{f"{user_field}_id__in": eligible_user_ids()})
