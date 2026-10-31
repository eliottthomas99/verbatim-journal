from tools.e2e_checks import check_answer


def test_absent_answer_is_not_automatically_correct():
    result = check_answer("You met a dinosaur.", ["unrelated"], [], [], "absent")
    assert result["answer_smoke_check"] is False
    assert result["retrieval_check"] is None


def test_absent_date_requires_empty_sources_and_abstention():
    assert check_answer("No recordings in that period.", [], [], [], "absent-date") == {
        "answer_smoke_check": True, "retrieval_check": True}
    assert check_answer("No recordings.", ["wrong-date"], [], [], "absent-date")["retrieval_check"] is False


def test_dated_check_rejects_extra_sources():
    assert check_answer("fact", ["right", "wrong-date"], ["fact"], ["right"], "date")["retrieval_check"] is False
