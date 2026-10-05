"""가입·비밀번호 확인에서 함께 사용하는 검증기."""

import re

from django.core.exceptions import ValidationError


class LettersAndDigitsValidator:
    def validate(self, password, user=None):
        if not re.search(r"[A-Za-z]", password) or not re.search(r"[0-9]", password):
            raise ValidationError(
                self.get_help_text(), code="password_no_letters_and_digits"
            )

    def get_help_text(self):
        return "비밀번호에는 영문과 숫자가 모두 포함되어야 한다."
