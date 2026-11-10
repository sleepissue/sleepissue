"""측정 집계기 회귀용 인공 자료다. 실제 기기30분 시험과 구분한다."""
import copy
import pathlib
import runpy
import unittest

analyze = runpy.run_path(str(pathlib.Path(__file__).with_name('analyze.py')))['analyze']


class AnalysisTest(unittest.TestCase):
    def setUp(self):
        self.records = {'published': [], 'consumed': [], 'saves': [], 'lifecycle': [], 'dropped': 0, 'inputCreates': 1}
        for i in range(18000):
            at = 1000 + i * 100
            self.records['published'].append({'sessionId': 1, 'sequence': i+1, 'atMs': at})
            self.records['consumed'].append({'sessionId': 1, 'sequence': i+1, 'atMs': at+1,
                'tMs': at+1_000_000_000, 'gear': 'D', 'availability': 'AVAILABLE', 'leaseUntilMs': at+3000})
        self.result = {'windowStartMs': 1000, 'windowEndMs': 1_801_000, 'procedureCompleted': True,
            'startHub': {'emittedFrames': 0, 'skippedDeadlines': 0, 'nextPublicationAtMs': 1000},
            'endHub': {'emittedFrames': 18000, 'skippedDeadlines': 0},
            'startPrecise': {'continuousDriveMs': 0, 'trip': {'tripId': 'fixture', 'last24hDriveSec': 0}},
            'endPrecise': {'continuousDriveMs': 1800000, 'trip': {'tripId': 'fixture', 'last24hDriveSec': 1800}}}

    def result_for(self):
        return analyze(self.records, self.result, [])

    def test_continuous_frame_path(self):
        self.assertTrue(self.result_for()['frameDeliveryAndDeadlineCriterionMet'])

    def test_completed_frame_window_is_separate_from_cleanup_timeout(self):
        self.result['procedureCompleted'] = None
        r = self.result_for()
        self.assertTrue(r['frameDeliveryAndDeadlineCriterionMet'])
        self.assertIsNone(r['procedureCompleted'])

    def test_missing_published_sequence_is_not_hidden_by_counter(self):
        del self.records['published'][100]
        r = self.result_for()
        self.assertEqual(1, r['producer']['sequenceGaps'])
        self.assertFalse(r['frameDeliveryAndDeadlineCriterionMet'])

    def test_missing_last_consumer_is_counted(self):
        self.records['consumed'].pop()
        r = self.result_for()
        self.assertEqual([[1, 18000]], [list(v) for v in r['missingConsumerKeys']])
        self.assertFalse(r['frameDeliveryAndDeadlineCriterionMet'])

    def test_duplicate_consumer_is_not_normal_delivery(self):
        self.records['consumed'].append(self.records['consumed'][-1])
        self.assertFalse(self.result_for()['frameDeliveryAndDeadlineCriterionMet'])

    def test_skipped_deadline_fails_even_when_sequences_are_continuous(self):
        self.result['endHub']['skippedDeadlines'] = 1
        self.assertFalse(self.result_for()['frameDeliveryAndDeadlineCriterionMet'])

    def test_boundary_delivery_after_window_is_drained(self):
        self.records['consumed'][-1]['atMs'] = 1_801_005
        self.assertEqual([], self.result_for()['missingConsumerKeys'])

    def test_stale_quality_and_frame_delivery_are_separate(self):
        self.records['consumed'][100]['availability'] = 'STALE'
        self.records['consumed'][100]['gear'] = None
        r = self.result_for()
        self.assertTrue(r['frameDeliveryAndDeadlineCriterionMet'])
        self.assertEqual(100, r['gearIntervalDurationMs']['STALE'])

    def test_recorder_overflow_prevents_zero_loss_claim(self):
        self.records['dropped'] = 1
        self.assertFalse(self.result_for()['frameDeliveryAndDeadlineCriterionMet'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
