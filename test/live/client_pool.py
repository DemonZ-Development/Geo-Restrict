"""Persistent parser process; every request still creates an independent socket."""
import concurrent.futures
import json
import subprocess
import threading
from pathlib import Path

class ClientPool:
    def __init__(self, directory):
        self.lock=threading.Lock();self.requests={};self.sequence=0
        self.errors=(directory/'client-parser.log').open('a')
        self.process=subprocess.Popen(['node',str(Path(__file__).with_name('client-pool.cjs'))],
            stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=self.errors,text=True,bufsize=1)
        threading.Thread(target=self.read,daemon=True).start()

    def read(self):
        for line in self.process.stdout:
            try:message=json.loads(line)
            except json.JSONDecodeError:continue
            with self.lock:future=self.requests.pop(message.get('id'),None)
            if future:future.set_result(message['result'])
        with self.lock:
            for future in self.requests.values():future.set_exception(RuntimeError('Protocol parser exited'))
            self.requests.clear()

    def connect(self, options):
        future=concurrent.futures.Future()
        with self.lock:
            self.sequence+=1;identifier=self.sequence;self.requests[identifier]=future
            self.process.stdin.write(json.dumps({'id':identifier,'options':options})+'\n')
            self.process.stdin.flush()
        return future.result(timeout=35)

    def close(self):
        self.process.stdin.close()
        try:self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:self.process.kill();self.process.wait()
        self.errors.close()
