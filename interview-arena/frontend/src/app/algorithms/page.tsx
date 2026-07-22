import { Suspense } from 'react';
import { QuestionList } from '@/components/QuestionList';

export default function AlgorithmsPage() {
  return (
    <Suspense fallback={<div className="p-12 text-center text-ink/40">加载中...</div>}>
      <QuestionList
        title="算法"
        subtitle="LeetCode 大厂算法题,按难度刷题巩固核心套路"
        fixedType="PROGRAMMING"
      />
    </Suspense>
  );
}
